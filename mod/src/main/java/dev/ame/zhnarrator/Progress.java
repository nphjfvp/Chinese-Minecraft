package dev.ame.zhnarrator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class Progress {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final long SAVE_EVERY_MS = 15000L;
   public Map<String, Progress.World> worlds = new HashMap<>();
   public Map<String, Integer> correctTotal = new HashMap<>();
   public Map<String, Long> lastCorrectAt = new HashMap<>();
   public int deaths;
   /** Learning cards by word (see {@link #cardKey}), shared by all worlds. */
   public Map<String, Progress.Card> cards = new HashMap<>();
   /** Time spent playing, in ms. Review intervals count only this time. */
   public long playMs;
   private transient Progress.World current = new Progress.World();
   private transient Path file;
   private transient boolean dirty;
   private transient long lastSave;
   private transient Map<String, Long> lastAskedAt = new HashMap<>();
   private transient Map<String, Integer> keyCorrect;

   public static Progress load(Path file) {
      Progress p = null;
      if (Files.exists(file)) {
         try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p = (Progress)GSON.fromJson(r, Progress.class);
         } catch (RuntimeException | IOException var7) {
            ZhNarratorClient.LOGGER.warn("Could not read {}: {}", file, var7.toString());
         }
      }

      if (p == null) {
         p = new Progress();
      }

      if (p.worlds == null) {
         p.worlds = new HashMap<>();
      }

      if (p.correctTotal == null) {
         p.correctTotal = new HashMap<>();
      }

      if (p.lastCorrectAt == null) {
         p.lastCorrectAt = new HashMap<>();
      }

      if (p.cards == null) {
         p.cards = new HashMap<>();
      }

      p.current = new Progress.World();
      p.file = file;
      p.lastAskedAt = new HashMap<>();
      return p;
   }

   public void switchWorld(String key) {
      this.current = this.worlds.computeIfAbsent(key, k -> new Progress.World());
      if (this.current.pledge == null) {
         this.current.pledge = new java.util.ArrayList<>();
      }

      this.keyCorrect = null;
      this.save();
      ZhNarratorClient.LOGGER.info("progress for world '{}': {} phrases heard", key, this.current.heard.size());
   }

   public Progress.World world() {
      return this.current;
   }

   public void heard(String id) {
      this.current.heard.merge(id, 1, Integer::sum);
      this.dirty = true;
   }

   /** Records an answer. Returns true when this answer made the word mastered. */
   public boolean answered(Phrase p, boolean correct) {
      boolean mastered = this.updateCard(p, correct);
      int[] stats = this.current.quiz.computeIfAbsent(p.id(), k -> new int[2]);
      stats[0]++;
      this.lastAskedAt.put(p.id(), System.currentTimeMillis());
      if (correct) {
         stats[1]++;
         this.lastCorrectAt.put(p.id(), System.currentTimeMillis());
         this.correctTotal.merge(p.id(), 1, Integer::sum);
         this.keyCorrect = null;
      }

      this.save();
      return mastered;
   }

   /** Review intervals in minutes of play time, one per level. */
   private static final long[] REVIEW_MINUTES = {10L, 30L, 120L, 360L, 1440L, 4320L, 10080L};

   private static long reviewMs(int level) {
      return REVIEW_MINUTES[Math.min(level, REVIEW_MINUTES.length - 1)] * 60000L;
   }

   private boolean updateCard(Phrase p, boolean correct) {
      Progress.Card card = this.cards.get(cardKey(p));
      if (card == null) {
         return false;
      } else if (!card.mastered) {
         card.streak = correct ? card.streak + 1 : 0;
         if (card.streak >= Math.max(1, ZhNarratorClient.config.masterAfterCorrect)) {
            card.mastered = true;
            card.level = 0;
            card.dueAt = this.playMs + reviewMs(0);
            return true;
         } else {
            return false;
         }
      } else {
         card.level = correct ? Math.min(card.level + 1, REVIEW_MINUTES.length - 1) : 0;
         card.dueAt = this.playMs + reviewMs(card.level);
         return false;
      }
   }

   /** The word a phrase teaches: its item, block or creature, or the phrase itself (walk, jump …). */
   public static String cardKey(Phrase p) {
      return p.key() != null ? p.key() : p.id();
   }

   @org.jetbrains.annotations.Nullable
   public Progress.Card card(String key) {
      return this.cards.get(key);
   }

   /** True while the word of this phrase is one of the words being learned right now. */
   public boolean isActive(Phrase p) {
      Progress.Card card = this.cards.get(cardKey(p));
      return card != null && !card.mastered;
   }

   public boolean isMastered(String key) {
      Progress.Card card = this.cards.get(key);
      return card != null && card.mastered;
   }

   public int masteredCount() {
      int n = 0;

      for (Progress.Card card : this.cards.values()) {
         if (card.mastered) {
            n++;
         }
      }

      return n;
   }

   /**
    * Tops the words being learned up to {@code limit}. Only words that can be asked in this world
    * count; new words are the ones heard most often.
    */
   public List<String> fillActive(Map<String, Integer> heardByCard, int limit) {
      int active = 0;
      List<String> fresh = new java.util.ArrayList<>();

      for (String key : heardByCard.keySet()) {
         Progress.Card card = this.cards.get(key);
         if (card == null) {
            fresh.add(key);
         } else if (!card.mastered) {
            active++;
         }
      }

      fresh.sort(java.util.Comparator.<String>comparingInt(heardByCard::get).reversed().thenComparing(k -> k));
      List<String> added = new java.util.ArrayList<>();

      for (String key : fresh) {
         if (active >= limit) {
            break;
         }

         Progress.Card card = new Progress.Card();
         card.since = this.playMs;
         this.cards.put(key, card);
         added.add(key);
         active++;
      }

      if (!added.isEmpty()) {
         this.dirty = true;
      }

      return added;
   }

   /** Items taken by the angry teacher or by death, as saved item data; earned back by right answers. */
   public void pledge(List<String> items) {
      if (!items.isEmpty()) {
         this.current.pledge.addAll(items);
         this.save();
      }
   }

   /** Removes and returns one random pledged item, or null when the pledge is empty. */
   @org.jetbrains.annotations.Nullable
   public String takePledged(java.util.Random random) {
      List<String> pledge = this.current.pledge;
      if (pledge.isEmpty()) {
         return null;
      } else {
         String item = pledge.remove(random.nextInt(pledge.size()));
         this.save();
         return item;
      }
   }

   public int pledgeSize() {
      return this.current.pledge.size();
   }

   public void addPlayTime(long ms) {
      this.playMs += ms;
      this.dirty = true;
   }

   public int correctEver(String id) {
      return this.correct(id);
   }

   public int learnedWords(PhraseBook phrases) {
      return this.masteredCount();
   }

   public void recordDeath(boolean hardcore) {
      this.current.deaths++;
      if (hardcore) {
         this.deaths++;
      }

      this.current.heartsLost = 0;
      this.save();
   }

   public long sinceAskedMs(String id, long now) {
      Long at = this.lastAskedAt.get(id);
      return at == null ? 4611686018427387903L : now - at;
   }

   public int wordCorrect(String key) {
      if (this.keyCorrect == null) {
         this.keyCorrect = new HashMap<>();

         for (Entry<String, int[]> e : this.current.quiz.entrySet()) {
            Phrase p = ZhNarratorClient.phrases == null ? null : ZhNarratorClient.phrases.fixed(e.getKey()).orElse(null);
            if (p != null && p.key() != null) {
               this.keyCorrect.merge(p.key(), e.getValue()[1], Math::max);
            }
         }
      }

      return this.keyCorrect.getOrDefault(key, 0);
   }

   public int asked(String id) {
      int[] s = this.current.quiz.get(id);
      return s == null ? 0 : s[0];
   }

   public int correct(String id) {
      int[] s = this.current.quiz.get(id);
      return s == null ? 0 : s[1];
   }

   public int heartsLost() {
      return this.current.heartsLost;
   }

   public void setHeartsLost(int n) {
      this.current.heartsLost = n;
      this.dirty = true;
   }

   public void tick() {
      long now = System.currentTimeMillis();
      if (this.dirty && now - this.lastSave > 15000L) {
         this.save();
      }
   }

   public void save() {
      if (this.file != null) {
         try {
            Files.createDirectories(this.file.getParent());

            try (Writer w = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
               GSON.toJson(this, w);
            }

            this.dirty = false;
            this.lastSave = System.currentTimeMillis();
         } catch (IOException var6) {
            ZhNarratorClient.LOGGER.warn("Could not write {}: {}", this.file, var6.toString());
         }
      }
   }

   public static final class Card {
      /** False while the word is being learned, true once it is only reviewed. */
      public boolean mastered;
      /** Correct answers in a row while being learned. */
      public int streak;
      /** Review level, an index into the review intervals. */
      public int level;
      /** Play time ({@link Progress#playMs}) at which the next review is due. */
      public long dueAt;
      /** Play time at which the word started being learned. */
      public long since;
   }

   public static final class World {
      public Map<String, Integer> heard = new HashMap<>();
      public Map<String, int[]> quiz = new HashMap<>();
      public int heartsLost;
      public int deaths;
      public List<String> pledge = new java.util.ArrayList<>();
   }
}
