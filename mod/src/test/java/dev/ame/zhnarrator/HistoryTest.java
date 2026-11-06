package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The sentence history and the translations of single words shown when hovering them. */
class HistoryTest {
   private static PhraseBook book;

   @BeforeAll
   static void loadPhrases() throws Exception {
      ZhNarratorClient.config = new NarratorConfig();
      try (Reader reader = Files.newBufferedReader(Path.of("../langpack-chinese/assets/zhnarrator_zh/phrases.json"), StandardCharsets.UTF_8)) {
         JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
         book = new PhraseBook(LanguagePacks.Meta.from("zhnarrator_zh", root.getAsJsonObject("meta")), root);
      }
      ZhNarratorClient.phrases = book;
   }

   @Test
   void wordsHaveTheirOwnTranslation() {
      assertEquals(Optional.of("dirt"), book.gloss("泥土"));
      assertEquals(Optional.of("dirt"), book.gloss("泥土，"), "a comma from counting does not matter");
      assertEquals(Optional.of("to dig"), book.gloss("挖"));
      assertEquals(Optional.of("I, me"), book.gloss("我"));
      assertTrue(book.gloss("在").orElse("").contains("-ing"));
      assertEquals(Optional.of("white"), book.gloss("白色的"));
   }

   @Test
   void everyWordOfEverySentenceCanBeHovered() {
      List<String> missing = new ArrayList<>();
      for (Phrase p : book.all()) {
         for (Phrase.Part part : p.parts()) {
            if (book.gloss(part.text()).isEmpty()) {
               missing.add(p.id() + ": " + part.text());
            }
         }
      }
      assertEquals(List.of(), missing);
   }

   @Test
   void theHistoryKeepsTheLatestSentencesNewestFirst() throws Exception {
      Speaker speaker = new Speaker(book);
      Method remember = Speaker.class.getDeclaredMethod("remember", Phrase.class);
      remember.setAccessible(true);
      Phrase dirt = book.fixed("break.dirt").orElseThrow();
      Phrase walk = book.fixed("walk").orElseThrow();

      remember.invoke(speaker, dirt);
      remember.invoke(speaker, dirt);
      remember.invoke(speaker, walk);
      assertEquals(List.of(walk, dirt), speaker.history(), "newest first, a repeat right after is kept once");

      for (int i = 0; i < 50; i++) {
         remember.invoke(speaker, i % 2 == 0 ? dirt : walk);
      }
      assertEquals(40, speaker.history().size(), "only the last 40 are kept");
   }
}
