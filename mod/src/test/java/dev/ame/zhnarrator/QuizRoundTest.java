package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A round asks the beginning, the word, the whole sentence and, with the voice helper, speaking it. */
class QuizRoundTest {
   private static PhraseBook book;

   @BeforeAll
   static void loadPhrases() throws Exception {
      try (Reader reader = Files.newBufferedReader(Path.of("../langpack-chinese/assets/zhnarrator_zh/phrases.json"), StandardCharsets.UTF_8)) {
         JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
         book = new PhraseBook(LanguagePacks.Meta.from("zhnarrator_zh", root.getAsJsonObject("meta")), root);
      }
   }

   @BeforeEach
   void setUp() {
      ZhNarratorClient.config = new NarratorConfig();
      ZhNarratorClient.phrases = book;
   }

   private static Phrase phrase(String id) {
      return book.fixed(id).orElseThrow();
   }

   private static QuizRound round(String id, boolean speech) {
      Phrase p = phrase(id);
      return QuizRound.build(book, p, new Random(7), speech, List.of(p));
   }

   private static List<QuizRound.Step> steps(QuizRound round) {
      return round.questions.stream().map(QuizRound.Question::step).toList();
   }

   private static Set<String> texts(QuizRound.Question q) {
      Set<String> out = new HashSet<>();
      q.options().forEach(o -> out.add(o.text()));
      return out;
   }

   @Test
   void aSentenceAboutAThingAsksAllFourQuestions() {
      QuizRound round = round("break.dirt", true);
      assertEquals(List.of(QuizRound.Step.FRAME, QuizRound.Step.WORD, QuizRound.Step.SENTENCE, QuizRound.Step.SPEECH), steps(round));

      QuizRound.Question frame = round.questions.get(0);
      assertEquals("我在挖…", frame.question().text());
      assertEquals("I am digging the dirt.", frame.question().en(), "the beginning is asked with the whole sentence's English");
      assertEquals(4, texts(frame).size());
      assertTrue(frame.options().contains(frame.question()));
      // Wrong beginnings come from other sentences about dirt first: 我捡到…, 我在放…, 我扔了…
      assertTrue(texts(frame).contains("我在放…"), texts(frame).toString());

      QuizRound.Question word = round.questions.get(1);
      assertEquals(phrase("hold.dirt"), word.question());
      assertEquals("dirt", word.question().en());
      assertEquals(4, texts(word).size());
      assertTrue(word.options().stream().allMatch(o -> o.action().equals("hold") || o.action().equals("word")));
      assertEquals(phrase("hold.dirt"), round.word, "after the word question the teacher says 泥土");
   }

   @Test
   void withoutTheVoiceHelperThereIsNoSpeakingQuestion() {
      assertEquals(List.of(QuizRound.Step.FRAME, QuizRound.Step.WORD, QuizRound.Step.SENTENCE), steps(round("break.dirt", false)));
   }

   @Test
   void aSentenceWithoutAThingAsksTheVerb() {
      QuizRound round = round("walk", false);
      assertEquals(List.of(QuizRound.Step.FRAME, QuizRound.Step.WORD, QuizRound.Step.SENTENCE), steps(round));
      assertEquals("我在…", round.questions.get(0).question().text());
      QuizRound.Question verb = round.questions.get(1);
      assertEquals("走路", verb.question().text());
      assertEquals("我在 …", verb.titleArg(), "the prompt shows where the verb goes");
      assertEquals(4, texts(verb).size());
      assertNull(verb.question().key());
   }

   @Test
   void aSentenceStartingWithItsVerbHasNoBeginningQuestion() {
      assertEquals(List.of(QuizRound.Step.WORD, QuizRound.Step.SENTENCE), steps(round("rain", false)));
   }

   @Test
   void aBareWordIsAskedOnce() {
      assertEquals(List.of(QuizRound.Step.SENTENCE), steps(round("hold.dirt", false)));
   }

   @Test
   void wordsMadeOfSeveralPartsAreFound() {
      QuizRound.Split split = QuizRound.split(book, phrase("break.white_wool"));
      assertNotNull(split);
      assertEquals("我在拆", split.frame().stream().map(Phrase.Part::text).reduce("", String::concat));
      assertEquals("白色的羊毛", split.word().bare());
   }

   @Test
   void everySentenceGivesAValidRound() {
      int rounds = 0;
      int withFrame = 0;
      int withWord = 0;
      Random random = new Random(1);

      for (Phrase p : book.all()) {
         if (PhraseBook.isFragment(p)) {
            continue;
         }
         QuizRound round = QuizRound.build(book, p, random, true, List.of(p));
         rounds++;
         for (QuizRound.Question q : round.questions) {
            if (q.step() == QuizRound.Step.SPEECH) {
               continue;
            }
            assertTrue(q.options().contains(q.question()), p.id() + " " + q.step());
            assertEquals(q.options().size(), texts(q).size(), p.id() + " " + q.step() + " has a duplicate answer");
            if (q.step() == QuizRound.Step.FRAME) {
               withFrame++;
            } else if (q.step() == QuizRound.Step.WORD) {
               withWord++;
            }
         }
      }

      System.out.printf("rounds %d, with beginning question %d, with word question %d%n", rounds, withFrame, withWord);
      assertTrue(withFrame > rounds / 2 && withWord > rounds / 2, "most sentences get all questions");
   }

   @Test
   void thePledgeGivesBackRandomStacksUntilEmpty() {
      Progress progress = new Progress();
      progress.pledge(List.of("a", "b", "c"));
      assertEquals(3, progress.pledgeSize());
      Set<String> back = new HashSet<>();
      Random random = new Random(3);
      for (int i = 0; i < 3; i++) {
         back.add(progress.takePledged(random));
      }
      assertEquals(Set.of("a", "b", "c"), back);
      assertNull(progress.takePledged(random));
   }
}
