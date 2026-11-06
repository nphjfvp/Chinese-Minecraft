package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The quiz teaches a few words at a time and reviews mastered ones with spaced repetition. */
class LearningTest {
   private static final Path PHRASES = Path.of("../langpack-chinese/assets/zhnarrator_zh/phrases.json");
   private static final long MINUTE = 60_000L;
   private static PhraseBook book;

   private Progress progress;
   private QuizManager quiz;
   /** Card key → one phrase of it, most heard first. */
   private final Map<String, Phrase> heardCards = new LinkedHashMap<>();

   @BeforeAll
   static void loadPhrases() throws Exception {
      try (Reader reader = Files.newBufferedReader(PHRASES, StandardCharsets.UTF_8)) {
         JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
         book = new PhraseBook(LanguagePacks.Meta.from("zhnarrator_zh", root.getAsJsonObject("meta")), root);
      }
   }

   @BeforeEach
   void setUp() {
      ZhNarratorClient.config = new NarratorConfig();
      ZhNarratorClient.phrases = book;
      this.progress = new Progress();
      ZhNarratorClient.progress = this.progress;
      this.quiz = new QuizManager(book, new Speaker(book), this.progress);

      // The player has heard one sentence each about 40 different words, the first ones most often.
      List<Phrase> sorted = new ArrayList<>(book.all());
      sorted.sort(Comparator.comparing(Phrase::id));
      for (Phrase p : sorted) {
         if (this.heardCards.size() == 40) {
            break;
         }
         if (!PhraseBook.isFragment(p) && !this.heardCards.containsKey(Progress.cardKey(p))) {
            this.progress.world().heard.put(p.id(), 100 - this.heardCards.size());
            this.heardCards.put(Progress.cardKey(p), p);
         }
      }
   }

   private Phrase pick() throws Exception {
      Method pick = QuizManager.class.getDeclaredMethod("pick", boolean.class);
      pick.setAccessible(true);
      return (Phrase)pick.invoke(this.quiz, false);
   }

   private Set<String> activeCards() {
      Set<String> active = new HashSet<>();
      this.progress.cards.forEach((key, card) -> {
         if (!card.mastered) {
            active.add(key);
         }
      });
      return active;
   }

   private List<String> mostHeard(int n) {
      return new ArrayList<>(this.heardCards.keySet()).subList(0, n);
   }

   @Test
   void onlyTheActiveWordsAreAsked() throws Exception {
      Set<String> asked = new HashSet<>();
      for (int i = 0; i < 300; i++) {
         asked.add(Progress.cardKey(this.pick()));
      }

      assertEquals(Set.copyOf(this.mostHeard(5)), this.activeCards(), "the five most heard words are learned first");
      assertEquals(this.activeCards(), asked, "every question is about one of them, and all of them come up");
   }

   @Test
   void activeWordsFollowTheSetting() throws Exception {
      ZhNarratorClient.config.activeWords = 3;
      this.pick();
      assertEquals(Set.copyOf(this.mostHeard(3)), this.activeCards());
   }

   @Test
   void aMasteredWordMakesRoomForTheNextOne() throws Exception {
      this.pick();
      Phrase first = this.heardCards.get(this.mostHeard(1).getFirst());

      assertFalse(this.progress.answered(first, true), "one right answer is not enough");
      assertTrue(this.progress.answered(first, true), "two right answers in a row master the word");
      assertTrue(this.progress.isMastered(Progress.cardKey(first)));

      this.pick();
      Set<String> expected = new HashSet<>(this.mostHeard(6));
      expected.remove(Progress.cardKey(first));
      assertEquals(expected, this.activeCards(), "the sixth most heard word joins");
   }

   @Test
   void aWrongAnswerStartsTheStreakAgain() throws Exception {
      this.pick();
      Phrase first = this.heardCards.get(this.mostHeard(1).getFirst());

      this.progress.answered(first, true);
      this.progress.answered(first, false);
      assertFalse(this.progress.answered(first, true));
      assertTrue(this.progress.answered(first, true));
   }

   @Test
   void dueReviewsComeFirstAndIntervalsGrow() throws Exception {
      this.pick();
      String key = this.mostHeard(1).getFirst();
      Phrase first = this.heardCards.get(key);
      this.progress.answered(first, true);
      this.progress.answered(first, true);
      Progress.Card card = this.progress.card(key);
      assertNotNull(card);

      this.progress.addPlayTime(9 * MINUTE);
      for (int i = 0; i < 50; i++) {
         assertFalse(key.equals(Progress.cardKey(this.pick())), "not due before 10 minutes of play");
      }

      this.progress.addPlayTime(MINUTE);
      assertEquals(key, Progress.cardKey(this.pick()), "due after 10 minutes, asked before the words being learned");

      this.progress.answered(first, true);
      assertEquals(this.progress.playMs + 30 * MINUTE, card.dueAt, "next review after 30 minutes");
      this.progress.answered(first, true);
      assertEquals(this.progress.playMs + 120 * MINUTE, card.dueAt, "then after 2 hours");

      this.progress.answered(first, false);
      assertEquals(this.progress.playMs + 10 * MINUTE, card.dueAt, "a wrong answer starts with a short interval again");
      assertTrue(card.mastered, "but the word stays mastered and does not take a learning slot");
   }

   @Test
   void dyingKeepsWhatWasLearned() throws Exception {
      this.pick();
      String key = this.mostHeard(1).getFirst();
      this.progress.answered(this.heardCards.get(key), true);
      this.progress.answered(this.heardCards.get(key), true);

      this.progress.recordDeath(false);
      assertTrue(this.progress.isMastered(key));
      assertEquals(1, this.progress.learnedWords(book));
   }

   @Test
   void sentencesAboutActiveWordsAreMarked() throws Exception {
      this.pick();
      for (Map.Entry<String, Phrase> e : this.heardCards.entrySet()) {
         assertEquals(this.mostHeard(5).contains(e.getKey()), this.progress.isActive(e.getValue()), e.getKey());
      }
   }
}
