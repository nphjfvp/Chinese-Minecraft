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

      p.current = new Progress.World();
      p.file = file;
      p.lastAskedAt = new HashMap<>();
      return p;
   }

   public void switchWorld(String key) {
      this.current = this.worlds.computeIfAbsent(key, k -> new Progress.World());
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

   public void answered(Phrase p, boolean correct) {
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
   }

   public int correctEver(String id) {
      return this.correct(id);
   }

   public boolean isLearned(String id) {
      return this.correctEver(id) >= Math.max(1, ZhNarratorClient.config.learnedAfterCorrect);
   }

   public long restLeft(String id, long now) {
      Long at = this.lastCorrectAt.get(id);
      if (at == null) {
         return 0L;
      } else {
         NarratorConfig c = ZhNarratorClient.config;
         if (this.isLearned(id) && "until_death".equals(c.learnedMode)) {
            return 4611686018427387903L;
         } else {
            float minutes = this.isLearned(id) ? c.learnedRestMinutes : c.quizRepeatAfterMinutes;
            return Math.max(0L, (long)(minutes * 60000.0F) - (now - at));
         }
      }
   }

   public int learnedWords(PhraseBook phrases) {
      return this.countWords(phrases, Math.max(1, ZhNarratorClient.config.learnedAfterCorrect), Integer.MAX_VALUE);
   }

   private int countWords(PhraseBook phrases, int minCorrect, int maxCorrect) {
      Set<String> words = new HashSet<>();

      for (Entry<String, int[]> e : this.current.quiz.entrySet()) {
         int correct = e.getValue()[1];
         if (correct >= minCorrect && correct <= maxCorrect) {
            Phrase p = phrases.fixed(e.getKey()).orElse(null);
            words.add(p != null && p.key() != null ? p.key() : e.getKey());
         }
      }

      return words.size();
   }

   public void recordDeath(boolean hardcore) {
      this.current.deaths++;
      if (hardcore) {
         this.deaths++;
      }

      this.current.heartsLost = 0;
      if ("until_death".equals(ZhNarratorClient.config.learnedMode)) {
         for (int[] stats : this.current.quiz.values()) {
            stats[1] = 0;
         }

         this.lastCorrectAt.keySet().removeIf(id -> this.current.quiz.containsKey(id));
         this.keyCorrect = null;
      }

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

   public static final class World {
      public Map<String, Integer> heard = new HashMap<>();
      public Map<String, int[]> quiz = new HashMap<>();
      public int heartsLost;
      public int deaths;
   }
}
