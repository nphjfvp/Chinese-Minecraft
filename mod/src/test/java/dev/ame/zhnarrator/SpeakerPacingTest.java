package dev.ame.zhnarrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Narration says at most one sentence per pause, and only about what is happening right now. */
class SpeakerPacingTest {
   private static PhraseBook book;
   private Speaker speaker;

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
      this.speaker = new Speaker(book);
   }

   private Phrase phrase(String id) {
      return book.fixed(id).orElseThrow();
   }

   /** Pretends the last sentence started {@code agoMs} ago. */
   private void lastStarted(long agoMs) throws Exception {
      Field startedAt = Speaker.class.getDeclaredField("startedAt");
      startedAt.setAccessible(true);
      startedAt.setLong(this.speaker, System.currentTimeMillis() - agoMs);
   }

   private String next() throws Exception {
      Method next = Speaker.class.getDeclaredMethod("nextQueued", long.class);
      next.setAccessible(true);
      Object q = next.invoke(this.speaker, System.currentTimeMillis());
      if (q == null) {
         return null;
      }
      Method phrase = q.getClass().getDeclaredMethod("phrase");
      phrase.setAccessible(true);
      return ((Phrase)phrase.invoke(q)).id();
   }

   @Test
   void nothingIsSaidDuringThePause() throws Exception {
      this.lastStarted(4000L);
      this.speaker.say(this.phrase("break.dirt"));
      assertNull(this.next(), "4 s after the last sentence it is still too early");
   }

   @Test
   void afterThePauseTheCurrentEventIsSaid() throws Exception {
      this.lastStarted(11000L);
      this.speaker.say(this.phrase("break.dirt"));
      assertEquals("break.dirt", this.next());
      assertNull(this.next(), "the other events were dropped, not queued");
   }

   @Test
   void eventsFromDuringThePauseAreDropped() throws Exception {
      this.lastStarted(11000L);
      this.speaker.say(this.phrase("break.dirt"));
      Field queue = Speaker.class.getDeclaredField("queue");
      queue.setAccessible(true);
      // Make the waiting event three seconds old, as if it happened during the pause.
      java.util.ArrayDeque<?> waiting = (java.util.ArrayDeque<?>)queue.get(this.speaker);
      Object old = waiting.peekFirst();
      waiting.clear();
      java.lang.reflect.Constructor<?> make = old.getClass().getDeclaredConstructors()[0];
      for (java.lang.reflect.Constructor<?> c : old.getClass().getDeclaredConstructors()) {
         if (c.getParameterCount() == 5) {
            make = c;
         }
      }
      make.setAccessible(true);
      @SuppressWarnings("unchecked")
      java.util.ArrayDeque<Object> raw = (java.util.ArrayDeque<Object>)waiting;
      Phrase dirt = this.phrase("break.dirt");
      raw.add(make.newInstance(dirt, false, false, java.util.List.of(dirt), System.currentTimeMillis() - 3000L));
      assertNull(this.next(), "a three second old event is no longer said");
   }

   @Test
   void theLeastHeardEventWins() throws Exception {
      ZhNarratorClient.progress.world().heard.put("walk", 50);
      ZhNarratorClient.progress.world().heard.put("break.dirt", 2);
      this.lastStarted(11000L);
      this.speaker.say(this.phrase("break.dirt"));
      this.speaker.say(this.phrase("walk"));
      assertEquals("break.dirt", this.next());
   }

   @Test
   void quizFeedbackIgnoresThePause() throws Exception {
      this.lastStarted(1000L);
      this.speaker.say(this.phrase("break.dirt"));
      this.speaker.sayPriority(this.phrase("angry"));
      assertEquals("angry", this.next());
      assertNull(this.next());
   }

   @Test
   void thePauseFollowsTheSetting() throws Exception {
      ZhNarratorClient.config.speechGapSeconds = 3.0F;
      this.lastStarted(4000L);
      this.speaker.say(this.phrase("break.dirt"));
      assertEquals("break.dirt", this.next());
   }
}
