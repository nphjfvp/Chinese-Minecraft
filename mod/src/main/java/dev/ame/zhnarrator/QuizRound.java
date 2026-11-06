package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * One quiz round about one sentence, e.g. 我在挖泥土 "I am digging the dirt":
 * <ol>
 *    <li>{@link Step#FRAME}: which beginning fits (我在挖…, 我捡到…, 我在放…)?</li>
 *    <li>{@link Step#WORD}: which word is "dirt" (泥土)? Sentences without a thing (我在走路) ask the verb.</li>
 *    <li>{@link Step#SENTENCE}: the whole sentence, as the quiz always did.</li>
 *    <li>{@link Step#SPEECH}: say the whole sentence, when the voice helper runs.</li>
 * </ol>
 * A step is left out when the sentence has no such part or there are not enough wrong answers to offer.
 */
final class QuizRound {
   enum Step {
      FRAME,
      WORD,
      SENTENCE,
      SPEECH
   }

   /** One question. {@code titleKey} is the translation key of the line above the prompt, {@code titleArg} its argument. */
   record Question(Step step, Phrase question, List<Phrase> options, String titleKey, String titleArg) {
   }

   private static final int OPTIONS = 4;

   final Phrase sentence;
   final List<Question> questions;
   /** The word the round is about (its audio is played after the word question), or null. */
   @Nullable
   final Phrase word;

   private QuizRound(Phrase sentence, List<Question> questions, @Nullable Phrase word) {
      this.sentence = sentence;
      this.questions = questions;
      this.word = word;
   }

   /** The sentence split into the beginning, the word and what follows. */
   record Split(List<Phrase.Part> frame, Phrase word, boolean keyed) {
   }

   static QuizRound build(PhraseBook book, Phrase sentence, Random random, boolean withSpeech, List<Phrase> sentenceOptions) {
      List<Question> questions = new ArrayList<>();
      Split split = split(book, sentence);
      Phrase word = null;
      if (split != null && !PhraseBook.isBareWord(sentence)) {
         word = split.word();
         if (!split.frame().isEmpty()) {
            Phrase frame = framePhrase(sentence, split.frame());
            List<Phrase> options = frameOptions(book, sentence, frame, random);
            if (options.size() >= 2) {
               questions.add(new Question(Step.FRAME, frame, options, "zhnarrator.quiz.prompt_frame", ""));
            }
         }

         List<Phrase> options = wordOptions(book, split, random);
         if (options.size() >= 2) {
            questions.add(
               split.keyed()
                  ? new Question(Step.WORD, split.word(), options, "zhnarrator.quiz.prompt_word", "")
                  : new Question(Step.WORD, split.word(), options, "zhnarrator.quiz.prompt_verb", join(split.frame(), false) + " …")
            );
         }
      }

      questions.add(new Question(Step.SENTENCE, sentence, sentenceOptions, "zhnarrator.quiz.prompt", ""));
      if (withSpeech) {
         questions.add(new Question(Step.SPEECH, sentence, List.of(), "", ""));
      }

      return new QuizRound(sentence, questions, word);
   }

   /** Finds the word in the sentence: the thing it is about, or for sentences without one its last verb. */
   @Nullable
   static Split split(PhraseBook book, Phrase sentence) {
      List<Phrase.Part> parts = sentence.parts();
      if (parts.isEmpty()) {
         return null;
      } else if (sentence.key() != null) {
         Phrase word = book.fixed("hold." + sentence.key()).or(() -> book.fixed("word." + sentence.key())).orElse(null);
         if (word == null) {
            return null;
         } else {
            String wanted = word.bare();

            for (int start = 0; start < parts.size(); start++) {
               StringBuilder joined = new StringBuilder();

               for (int end = start; end < parts.size(); end++) {
                  joined.append(parts.get(end).text());
                  if (joined.toString().equals(wanted)) {
                     return new Split(List.copyOf(parts.subList(0, start)), word, true);
                  }

                  if (joined.length() >= wanted.length()) {
                     break;
                  }
               }
            }

            return null;
         }
      } else {
         for (int i = parts.size() - 1; i >= 0; i--) {
            Phrase.Part part = parts.get(i);
            if (part.role().equals("verb")) {
               Phrase verb = new Phrase(sentence.id() + "#word", "word", null, part.text(), part.reading(), sentence.en(), List.of(part));
               return new Split(List.copyOf(parts.subList(0, i)), verb, false);
            }
         }

         return null;
      }
   }

   private static String join(List<Phrase.Part> parts, boolean reading) {
      StringBuilder out = new StringBuilder();

      for (Phrase.Part part : parts) {
         String piece = reading ? part.reading() : part.text();
         if (!piece.isEmpty()) {
            if (reading && !out.isEmpty()) {
               out.append(' ');
            }

            out.append(piece);
         }
      }

      return out.toString();
   }

   /** The beginning as its own answer: 我在挖… with the English of the whole sentence. */
   private static Phrase framePhrase(Phrase sentence, List<Phrase.Part> frame) {
      String reading = join(frame, true);
      return new Phrase(sentence.id() + "#frame", sentence.action(), sentence.key(), join(frame, false) + "…", reading.isEmpty() ? "" : reading + " …", sentence.en(), frame);
   }

   /** Beginnings of other sentences, about the same word first (我捡到…, 我在放…), then any. */
   private static List<Phrase> frameOptions(PhraseBook book, Phrase sentence, Phrase frame, Random random) {
      Map<String, Phrase> sameWord = new LinkedHashMap<>();
      Map<String, Phrase> others = new LinkedHashMap<>();
      List<Phrase> all = sorted(book);
      Collections.shuffle(all, random);

      for (Phrase other : all) {
         boolean same = sentence.key() != null && sentence.key().equals(other.key());
         if ((same || others.size() < OPTIONS * 2)
            && !other.id().equals(sentence.id())
            && !PhraseBook.isFragment(other)
            && !PhraseBook.isBareWord(other)) {
            Split split = split(book, other);
            if (split != null && !split.frame().isEmpty()) {
               Phrase candidate = framePhrase(other, split.frame());
               if (!candidate.text().equals(frame.text())) {
                  (same ? sameWord : others).putIfAbsent(candidate.text(), candidate);
               }
            }
         }
      }

      return options(frame, sameWord, others, random);
   }

   /** Other words: for a thing other things, for a verb other verbs. */
   private static List<Phrase> wordOptions(PhraseBook book, Split split, Random random) {
      Phrase word = split.word();
      Map<String, Phrase> candidates = new LinkedHashMap<>();
      List<Phrase> all = sorted(book);
      Collections.shuffle(all, random);

      for (Phrase other : all) {
         if (candidates.size() >= OPTIONS - 1) {
            break;
         }

         Phrase candidate;
         if (split.keyed()) {
            candidate = other.action().equals("hold") || other.action().equals("word") ? other : null;
         } else if (other.key() == null && !PhraseBook.isFragment(other)) {
            Split otherSplit = split(book, other);
            candidate = otherSplit == null ? null : otherSplit.word();
         } else {
            candidate = null;
         }

         if (candidate != null && !candidate.bare().equals(word.bare())) {
            candidates.putIfAbsent(candidate.bare(), candidate);
         }
      }

      return options(word, Map.of(), candidates, random);
   }

   private static List<Phrase> options(Phrase right, Map<String, Phrase> first, Map<String, Phrase> then, Random random) {
      List<Phrase> out = new ArrayList<>();
      out.add(right);
      java.util.Set<String> taken = new java.util.HashSet<>();
      taken.add(right.text());

      for (Map<String, Phrase> pool : List.of(first, then)) {
         for (Phrase p : pool.values()) {
            if (out.size() < OPTIONS && taken.add(p.text())) {
               out.add(p);
            }
         }
      }

      Collections.shuffle(out, random);
      return out;
   }

   /** A stable order before shuffling, so a seeded random gives the same round every time. */
   private static List<Phrase> sorted(PhraseBook book) {
      List<Phrase> all = new ArrayList<>(book.all());
      all.sort(java.util.Comparator.comparing((Function<Phrase, String>)Phrase::id));
      return all;
   }
}
