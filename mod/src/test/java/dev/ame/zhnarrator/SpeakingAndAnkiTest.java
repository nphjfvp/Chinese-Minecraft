package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Speaking in three stages, and the Anki export. */
class SpeakingAndAnkiTest {
   private static final Path PACK = Path.of("../langpack-chinese/assets/zhnarrator_zh");
   private static PhraseBook book;
   private Progress progress;

   @BeforeAll
   static void loadPhrases() throws Exception {
      ZhNarratorClient.config = new NarratorConfig();
      try (Reader reader = Files.newBufferedReader(PACK.resolve("phrases.json"), StandardCharsets.UTF_8)) {
         JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
         book = new PhraseBook(LanguagePacks.Meta.from("zhnarrator_zh", root.getAsJsonObject("meta")), root);
      }
      try (Reader reader = Files.newBufferedReader(PACK.resolve("lipsync.json"), StandardCharsets.UTF_8)) {
         book.lipsync(JsonParser.parseReader(reader).getAsJsonObject());
      }
      ZhNarratorClient.phrases = book;
   }

   @BeforeEach
   void setUp() {
      ZhNarratorClient.config = new NarratorConfig();
      this.progress = new Progress();
      ZhNarratorClient.progress = this.progress;
   }

   private static Phrase phrase(String id) {
      return book.fixed(id).orElseThrow();
   }

   @Test
   void eachWordIsRightOrWrong() {
      // 我 在 挖 泥土: wǒ zài wā ní tǔ, with wā misheard
      assertEquals(List.of(true, true, false, true), SpeakingStages.wordResults(phrase("break.dirt"), List.of(1.0F, 1.0F, 0.5F, 1.0F, 1.0F)));
      assertEquals(List.of(true, true, true, false), SpeakingStages.wordResults(phrase("break.dirt"), List.of(1.0F, 1.0F, 1.0F, 1.0F, 0.0F)),
         "泥土 is one word: one wrong syllable makes it wrong");
      assertEquals(List.of(), SpeakingStages.wordResults(phrase("break.dirt"), List.of(1.0F, 1.0F)), "an unexpected answer shape shows no colours");
   }

   @Test
   void theStagesGetFaster() {
      String voice = book.voices().getFirst();
      Phrase dirt = phrase("break.dirt");
      long normal = book.spokenMs(dirt.soundPath(voice, "normal"));
      long slow = book.spokenMs(dirt.soundPath(voice, "slow"));
      assertTrue(normal > 0 && slow > normal, normal + " / " + slow);

      assertEquals(0.0F, SpeakingStages.targetSeconds(SpeakingStages.SLOW, normal, slow), "word by word has no time limit");
      float faster = SpeakingStages.targetSeconds(SpeakingStages.FASTER, normal, slow);
      float normalTarget = SpeakingStages.targetSeconds(SpeakingStages.NORMAL, normal, slow);
      assertTrue(normalTarget < faster, normalTarget + " < " + faster);
      assertTrue(normalTarget > normal / 1000.0F, "normal speed allows a little more than the teacher needs");
      System.out.printf("break.dirt: teacher %.1f s (slow %.1f s); stage 2 at most %.1f s, stage 3 at most %.1f s%n",
         normal / 1000.0, slow / 1000.0, faster, normalTarget);

      assertTrue(SpeakingStages.fastEnough(SpeakingStages.SLOW, 30.0F, normal, slow));
      assertFalse(SpeakingStages.fastEnough(SpeakingStages.NORMAL, normalTarget + 0.1F, normal, slow));
      assertTrue(SpeakingStages.fastEnough(SpeakingStages.NORMAL, -1.0F, normal, slow), "an older helper without timing does not block");
   }

   @Test
   void theStageReachedIsRemembered() {
      Phrase dirt = phrase("break.dirt");
      this.progress.fillActive(Map.of("dirt", 1), 5);
      assertEquals(SpeakingStages.SLOW, this.progress.speechStage(dirt));
      this.progress.reachSpeechStage(dirt, SpeakingStages.FASTER);
      assertEquals(SpeakingStages.FASTER, this.progress.speechStage(phrase("pickup.dirt")), "the stage belongs to the word");
      this.progress.reachSpeechStage(dirt, SpeakingStages.SLOW);
      assertEquals(SpeakingStages.FASTER, this.progress.speechStage(dirt), "a stage once reached is not lost");
   }

   @Test
   void ankiCardsHaveTheWordInFrontAndASentenceOnTheBack() {
      this.progress.heard("break.dirt");
      this.progress.heard("break.dirt");
      this.progress.heard("walk");
      this.progress.fillActive(Map.of("dirt", 2, "walk", 1), 5);
      this.progress.answered(phrase("break.dirt"), true);
      this.progress.answered(phrase("break.dirt"), true);

      List<AnkiExport.Note> notes = AnkiExport.notes(book, this.progress);
      assertEquals(2, notes.size());
      AnkiExport.Note dirt = notes.getFirst();
      assertEquals("lod-zh-dirt", dirt.guid(), "mastered words come first; the id lets a new import update the card");
      assertTrue(dirt.front().contains("泥土") && dirt.front().contains("[sound:lod_zh_hold.dirt.ogg]"), dirt.front());
      assertTrue(dirt.back().contains("ní tǔ") && dirt.back().contains("dirt"), dirt.back());
      assertTrue(dirt.back().contains("我在挖泥土") && dirt.back().contains("[sound:lod_zh_break.dirt.ogg]"), "the sentence heard most");
      assertEquals(List.of("hold.dirt", "break.dirt"), dirt.sounds());
      assertTrue(dirt.tags().endsWith("mastered"));

      AnkiExport.Note walk = notes.get(1);
      assertTrue(walk.front().contains("我在走路"), "a word without a thing is learned as its sentence");
      assertTrue(walk.tags().endsWith("learning"));

      String text = AnkiExport.text(notes);
      assertTrue(text.startsWith("#separator:tab\n#html:true\n#notetype:Basic\n#deck:Learn or Die::Chinese\n#guid column:1\n#tags column:4\n"));
      for (String line : text.split("\n")) {
         if (!line.startsWith("#")) {
            assertEquals(4, line.split("\t", -1).length, line);
         }
      }
   }
}
