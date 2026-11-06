package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** The listening and tone questions, and how tone trouble changes what is asked. */
class ListenAndToneTest {
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
      ZhNarratorClient.progress = new Progress();
   }

   private static Phrase phrase(String id) {
      return book.fixed(id).orElseThrow();
   }

   private static QuizRound round(String id) {
      Phrase p = phrase(id);
      return QuizRound.build(book, p, new Random(5), true, List.of(p));
   }

   @Test
   void toneMarksAreReadAndWritten() {
      assertEquals(2, Tones.toneOf("ní"));
      assertEquals(3, Tones.toneOf("tǔ"));
      assertEquals(0, Tones.toneOf("de"));
      assertEquals("lü", Tones.bare("lǜ"));
      assertEquals(List.of("wā", "wá", "wǎ", "wà"), Tones.allTones("wā"));
      assertEquals(List.of("hōu", "hóu", "hǒu", "hòu"), Tones.allTones("hòu"), "ou takes the mark on o");
      assertEquals(List.of("guī", "guí", "guǐ", "guì"), Tones.allTones("guǐ"), "otherwise the last vowel");
      assertEquals(List.of("lǖ", "lǘ", "lǚ", "lǜ"), Tones.allTones("lǜ"));
      assertEquals(List.of("xiē", "xié", "xiě", "xiè"), Tones.allTones("xiè"), "e before i");
   }

   @Test
   void theFullRoundHasSixQuestions() {
      assertEquals(
         List.of(QuizRound.Step.LISTEN, QuizRound.Step.FRAME, QuizRound.Step.WORD, QuizRound.Step.TONE, QuizRound.Step.SENTENCE, QuizRound.Step.SPEECH),
         round("break.dirt").questions.stream().map(QuizRound.Question::step).toList()
      );
   }

   @Test
   void theListeningQuestionOffersSentencesThatSoundAlike() {
      QuizRound.Question listen = round("break.dirt").questions.getFirst();
      assertEquals("zhnarrator.quiz.listen", listen.titleKey(), "the screen plays the sentence and hides the English");
      assertEquals(phrase("break.dirt"), listen.question());
      Set<String> keys = new HashSet<>();
      listen.options().forEach(o -> keys.add(o.key()));
      assertEquals(Set.of("dirt"), keys, "all four are about dirt, so the verb has to be heard");
      assertEquals(4, listen.options().stream().map(Phrase::text).distinct().count());
   }

   @Test
   void theToneQuestionAsksOneSyllableOfTheWord() {
      QuizRound.Question tone = round("break.dirt").questions.get(3);
      assertEquals(QuizRound.Step.TONE, tone.step());
      String syllable = tone.question().text();
      assertTrue(Set.of("ní", "tǔ").contains(syllable), syllable);
      assertEquals(Tones.allTones(syllable), tone.options().stream().map(Phrase::text).toList(), "the four tones in order");
      assertTrue(tone.options().contains(tone.question()));
      assertTrue(tone.titleArg().startsWith(syllable.equals("ní") ? "泥 ni" : "土 tu"), tone.titleArg());
      assertEquals("dirt", tone.question().en());
   }

   @Test
   void everyToneQuestionIsValid() {
      int asked = 0;
      Random random = new Random(2);
      for (Phrase p : book.all()) {
         if (!p.action().equals("hold")) {
            continue;
         }
         QuizRound.Question q = QuizRound.toneQuestion(p, p.en(), random);
         if (q != null) {
            asked++;
            assertEquals(4, q.options().stream().map(Phrase::text).distinct().count(), p.id());
            assertTrue(q.options().contains(q.question()), p.id());
         }
      }
      assertTrue(asked > 400, "almost every word gets a tone question, got " + asked);
   }

   @Test
   void toneTroubleKeepsReviewsClose() {
      Progress progress = ZhNarratorClient.progress;
      progress.world().heard.put("break.dirt", 5);
      progress.fillActive(java.util.Map.of("dirt", 5), 5);
      Phrase dirt = phrase("break.dirt");
      progress.answered(dirt, true);
      progress.answered(dirt, true);
      Progress.Card card = progress.card("dirt");
      assertNotNull(card);

      progress.toneResult(dirt, false);
      progress.toneResult(dirt, false);
      progress.answered(dirt, true);
      assertEquals(0, card.level, "with wrong tones the next review stays 10 minutes away");

      progress.toneResult(dirt, true);
      progress.answered(dirt, true);
      assertEquals(1, card.level, "right tones again let the reviews move apart");
   }
}
