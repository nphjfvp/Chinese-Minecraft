package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** New worlds continue the learning or start from zero; pinyin stays until a word passed several reviews. */
class WorldsAndPinyinTest {
   private static PhraseBook book;
   private Progress progress;

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
      this.progress = new Progress();
      ZhNarratorClient.progress = this.progress;
   }

   private Phrase pick(QuizManager quiz) throws Exception {
      Method pick = QuizManager.class.getDeclaredMethod("pick", boolean.class);
      pick.setAccessible(true);
      return (Phrase)pick.invoke(quiz, false);
   }

   private void master(Phrase p) {
      this.progress.answered(p, true);
      this.progress.answered(p, true);
   }

   @Test
   void aNewWorldContinuesTheLearningByDefault() throws Exception {
      this.progress.switchWorld("first");
      for (int i = 0; i < 3; i++) {
         this.progress.heard("break.dirt");
      }
      QuizManager quiz = new QuizManager(book, new Speaker(book), this.progress);
      assertEquals("dirt", Progress.cardKey(pick(quiz)));
      master(book.fixed("break.dirt").orElseThrow());

      this.progress.switchWorld("second");
      assertTrue(this.progress.sharedLearning());
      assertTrue(this.progress.isMastered("dirt"), "what was mastered in the first world is mastered here");
      assertEquals(3, this.progress.heardForQuiz().getOrDefault("break.dirt", 0), "and the quiz may ask about it at once");
   }

   @Test
   void aNewWorldCanStartFromZero() {
      this.progress.switchWorld("first");
      this.progress.heard("break.dirt");
      this.progress.fillActive(Map.of("dirt", 1), 5);
      master(book.fixed("break.dirt").orElseThrow());

      ZhNarratorClient.config.newWorldLearning = "fresh";
      this.progress.switchWorld("second");
      assertFalse(this.progress.sharedLearning());
      assertFalse(this.progress.isMastered("dirt"));
      assertTrue(this.progress.heardForQuiz().isEmpty(), "nothing heard here yet");

      ZhNarratorClient.config.newWorldLearning = "continue";
      this.progress.switchWorld("first");
      assertTrue(this.progress.isMastered("dirt"), "the first world keeps its learning; the setting is for new worlds only");
      this.progress.switchWorld("second");
      assertFalse(this.progress.sharedLearning(), "and the second world stays on its own");
   }

   @Test
   void pinyinStaysUntilFourReviewsPassed() {
      Phrase dirt = book.fixed("break.dirt").orElseThrow();
      this.progress.fillActive(Map.of("dirt", 1), 5);
      master(dirt);
      assertFalse(this.progress.pinyinHidden("dirt"), "mastered is not enough");

      for (int review = 1; review <= 3; review++) {
         this.progress.answered(dirt, true);
         assertFalse(this.progress.pinyinHidden("dirt"), "after review " + review);
      }

      this.progress.answered(dirt, true);
      assertTrue(this.progress.pinyinHidden("dirt"), "after the fourth review the characters stand alone");

      this.progress.answered(dirt, false);
      assertFalse(this.progress.pinyinHidden("dirt"), "a wrong review brings the pinyin back");

      ZhNarratorClient.config.pinyinHideAfterReviews = 0;
      for (int review = 0; review < 10; review++) {
         this.progress.answered(dirt, true);
      }
      assertFalse(this.progress.pinyinHidden("dirt"), "0 keeps the pinyin forever");
   }
}
