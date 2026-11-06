package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class QuizManager {
   private static final long CALM_AFTER_DAMAGE_MS = 5000L;
   private static final long AFK_AFTER_MS = 20000L;
   private final PhraseBook phrases;
   private final Speaker speaker;
   private final Progress progress;
   private final SpeechClient speech = new SpeechClient();
   private final Random random = new Random();
   private static final long MIN_HOLD_MS = 400L;
   private static final long MAX_RECORD_MS = 6000L;
   private static final int MAX_ATTEMPTS = 3;
   private static final float UNCLEAR_CONFIDENCE = 0.45F;
   private QuizManager.Phase phase = QuizManager.Phase.NONE;
   private long deadline;
   private long recordingSince;
   private int attempts;
   @Nullable
   private SpeechClient.Heard bestHeard;
   private long lastHelperWarningAt;
   private long lastStatusPoll;
   private volatile boolean helperDone;
   private boolean hintNow;
   private int wrongStreak;
   private long frownUntil;
   private long shootAt;
   private int killInTicks;
   private long angryUntil;
   @Nullable
   private volatile SpeechClient.Heard heard;
   @Nullable
   private volatile Throwable heardError;
   private long lastQuizAt;
   private long lastTickAt;
   private long lastDamageAt;
   private long lastActiveAt;
   private float lastHealth = -1.0F;
   private Vec3 lastPos = Vec3.ZERO;
   private double lastYaw;
   private double lastPitch;
   @Nullable
   private Phrase pending;
   private boolean nothingToAsk;
   private long lastPickTryAt;
   private boolean helperUp;
   private long secondDingAt;
   private long deadlineTotalMs;
   @Nullable
   private QuizManager.Suspended suspended;
   private long busyUntil;

   public QuizManager(PhraseBook phrases, Speaker speaker, Progress progress) {
      this.phrases = phrases;
      this.speaker = speaker;
      this.progress = progress;
   }

   public void onJoinedWorld() {
      long now = System.currentTimeMillis();
      this.lastQuizAt = now;
      this.lastTickAt = now;
      this.nothingToAsk = this.progress.world().heard.isEmpty();
      this.lastPickTryAt = 0L;
      this.pending = null;
      this.phase = QuizManager.Phase.NONE;
   }

   public boolean hintNow() {
      return this.hintNow;
   }

   private void ding(Minecraft mc) {
      mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3F, 0.8F));
   }

   private static void playMaster(Minecraft mc, Identifier sound, float volume, float pitch) {
      mc.getSoundManager()
         .play(
            new SimpleSoundInstance(
               sound, SoundSource.MASTER, Math.min(1.0F, volume), pitch, RandomSource.create(), false, 0, Attenuation.NONE, 0.0, 0.0, 0.0, true
            )
         );
   }

   public void tick(Minecraft mc) {
      LocalPlayer player = mc.player;
      if (player != null) {
         long now = System.currentTimeMillis();
         if (this.killInTicks > 0 && --this.killInTicks == 0) {
            if (this.phrases.meta().shootSound().isEmpty()) {
               playMaster(mc, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST.location(), 1.0F, 0.6F);
            }

            Consequences.kill(mc);
         }

         if (this.secondDingAt != 0L && now >= this.secondDingAt) {
            this.secondDingAt = 0L;
            playMaster(mc, SoundEvents.EXPERIENCE_ORB_PICKUP.location(), ZhNarratorClient.config.learnedSoundVolume, 1.5F);
         }

         long gap = this.lastTickAt == 0L ? 0L : now - this.lastTickAt;
         this.lastTickAt = now;
         if (gap > 0L && (mc.gui.screen() != null || gap > 1000L)) {
            this.lastQuizAt += gap;
            if (this.phase != QuizManager.Phase.NONE) {
               this.deadline += gap;
            }
         }

         if (this.lastHealth >= 0.0F && player.getHealth() < this.lastHealth - 0.5F) {
            this.lastDamageAt = now;
         }

         this.lastHealth = player.getHealth();
         double yaw = player.getYRot();
         double pitch = player.getXRot();
         if (player.position().distanceToSqr(this.lastPos) > 1.0E-4 || yaw != this.lastYaw || pitch != this.lastPitch) {
            this.lastActiveAt = now;
         }

         this.lastPos = player.position();
         this.lastYaw = yaw;
         this.lastPitch = pitch;
         this.helperUp = this.speech.isAvailable();
         NarratorConfig c = ZhNarratorClient.config;
         if (this.suspended != null) {
            if (mc.gui.screen() == null) {
               QuizManager.Suspended s = this.suspended;
               this.suspended = null;
               mc.gui.setScreen(new QuizScreen(this, s.question(), s.options(), (float)s.remainingMs() / 1000.0F));
            }
         } else if (this.pending != null && this.phase != QuizManager.Phase.NONE) {
            this.tickSpeech(mc, now);
         } else if (c.enabled && !c.quizMode.equals("off") && this.pending == null && mc.gui.screen() == null) {
            boolean wantSpeech = c.quizMode.equals("speech") || c.quizMode.equals("mixed") && this.random.nextBoolean();
            if (this.nothingToAsk) {
               if (now - this.lastPickTryAt >= 1000L) {
                  this.lastPickTryAt = now;
                  if (this.hasCandidates()) {
                     this.nothingToAsk = false;
                     this.lastQuizAt = now;
                  }
               }
            } else if (!((float)(now - this.lastQuizAt) < c.quizIntervalSeconds * 1000.0F) && this.calmMoment(player, now)) {
               if (c.quizMode.equals("speech") && !this.helperUp) {
                  this.lastQuizAt = now - (long)(c.quizIntervalSeconds * 1000.0F) + 15000L;
                  VoiceHelper helper = ZhNarratorClient.voiceHelper;
                  if (now - this.lastHelperWarningAt > 60000L && (helper == null || !helper.busy() && !helper.modelLoading())) {
                     this.lastHelperWarningAt = now;
                     chat(
                        mc,
                        Component.literal("[Teacher] ")
                           .append(
                              Component.translatable("zhnarrator.voice.not_running", new Object[]{ZhNarratorClient.keyName(ZhNarratorClient.keySettings())})
                           )
                           .withStyle(ChatFormatting.YELLOW)
                     );
                  }
               } else {
                  Phrase question = this.pick(wantSpeech && this.helperUp);
                  if (question == null) {
                     this.nothingToAsk = true;
                     this.lastPickTryAt = now;
                  } else {
                     boolean speak = wantSpeech && this.helperUp && speechable(question);
                     ZhNarratorClient.LOGGER
                        .info(
                           "quiz picture for {} (key {}): {}",
                           new Object[]{question.id(), question.key(), WordIcons.iconFor(question).map(s -> s.getItem().toString()).orElse("none")}
                        );
                     this.pending = question;
                     this.lastQuizAt = now;
                     this.hintNow = false;
                     this.ding(mc);
                     if (speak) {
                        this.phase = QuizManager.Phase.WAITING;
                        this.deadline = now + (long)(c.quizAnswerSeconds * 1500.0F);
                        this.deadlineTotalMs = this.deadline - now;
                        this.heard = null;
                        this.heardError = null;
                        this.bestHeard = null;
                        this.attempts = 0;
                        ZhNarratorClient.LOGGER.info("quiz ask (speech) {} | {}", question.id(), question.en());
                     } else {
                        if (wantSpeech && !this.helperUp) {
                           ZhNarratorClient.LOGGER.info("speech helper not reachable at {}, asking with buttons", c.speechHelperUrl);
                        }

                        ZhNarratorClient.LOGGER.info("quiz ask {} | {}", question.id(), question.en());
                        mc.gui.setScreen(new QuizScreen(this, question, this.options(question), c.quizAnswerSeconds));
                     }
                  }
               }
            }
         }
      }
   }

   private void tickSpeech(Minecraft mc, long now) {
      boolean down = ZhNarratorClient.keyTalk.isDown();
      switch (this.phase) {
         case WAITING:
            if (down) {
               this.phase = QuizManager.Phase.RECORDING;
               this.recordingSince = now;
               this.lastStatusPoll = 0L;
               this.helperDone = false;
               this.speaker.stop();
               this.speech.start();
            } else if (now > this.deadline) {
               this.finishSpeech(mc, this.bestHeard);
            }
            break;
         case RECORDING:
            if (now - this.lastStatusPoll > 250L) {
               this.lastStatusPoll = now;
               this.speech.status().thenAccept(done -> this.helperDone = this.helperDone | done);
            }

            if (!down && now - this.recordingSince < 400L) {
               this.speech.stop(null, 0.0F);
               this.phase = QuizManager.Phase.WAITING;
               chat(mc, "§7hold the key while you speak");
            } else if (!down || this.helperDone || now - this.recordingSince > 6000L) {
               this.phase = QuizManager.Phase.CHECKING;
               this.speech.stop(this.pending.text(), ZhNarratorClient.config.speechPassScore).whenComplete((hx, e) -> {
                  this.heard = hx;
                  this.heardError = e;
               });
            }
            break;
         case CHECKING:
            if (this.heardError != null) {
               ZhNarratorClient.LOGGER.warn("speech helper failed: {}", this.heardError.toString());
               this.finishSpeech(mc, null);
            } else if (this.heard != null && this.heard.silent() && now < this.deadline) {
               chat(mc, Component.translatable("zhnarrator.voice.nothing_heard").withStyle(ChatFormatting.GRAY));
               this.heard = null;
               this.phase = QuizManager.Phase.WAITING;
            } else if (this.heard != null) {
               SpeechClient.Heard h = this.heard;
               this.heard = null;
               this.attempts++;
               boolean ok = h.verdict().equals("exact") || h.verdict().equals("pass");
               if (this.bestHeard == null || h.score() > this.bestHeard.score()) {
                  this.bestHeard = h;
               }

               boolean unclear = h.confidence() < 0.45F && !ok;
               if (!ok && this.attempts < 3 && now < this.deadline - 3000L) {
                  chat(mc, heardLine(h) + (unclear ? "  §7unclear, say it again" : "  §7try again"));
                  this.hintNow = true;
                  this.phase = QuizManager.Phase.WAITING;
               } else {
                  this.finishSpeech(mc, ok ? h : this.bestHeard);
               }
            } else if (now > this.deadline + 30000L) {
               this.finishSpeech(mc, this.bestHeard);
            }
      }
   }

   private void finishSpeech(Minecraft mc, @Nullable SpeechClient.Heard h) {
      Phrase question = this.pending;
      this.phase = QuizManager.Phase.NONE;
      this.heard = null;
      this.heardError = null;
      this.bestHeard = null;
      boolean ok = h != null && (h.verdict().equals("exact") || h.verdict().equals("pass"));
      chat(mc, h != null && !h.silent() ? heardLine(h) : "§7(nothing heard)");
      if (question != null) {
         this.answer(mc, question, ok);
      }
   }

   private static String heardLine(SpeechClient.Heard h) {
      return "§7heard: "
         + (h.text().isEmpty() ? "…" : h.text())
         + (h.reading().isEmpty() ? "" : "  " + h.reading())
         + String.format("  §8%d%%", Math.round(h.score() * 100.0F))
         + (h.toneErrors() > 0 ? "  §e(tones: " + h.toneErrors() + " off)" : "");
   }

   SpeechClient speech() {
      return this.speech;
   }

   private static void chat(Minecraft mc, Component text) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(text);
      }
   }

   private static void chat(Minecraft mc, String text) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal(text));
      }
   }

   public QuizManager.Phase phase() {
      return this.phase;
   }

   public void debugAskSpeech(String id) {
      Phrase q = this.phrases.fixed(id).orElse(null);
      if (q != null) {
         this.pending = q;
         this.phase = QuizManager.Phase.WAITING;
         this.deadline = System.currentTimeMillis() + 60000L;
         this.deadlineTotalMs = 60000L;
         this.heard = null;
         this.heardError = null;
         this.bestHeard = null;
         this.attempts = 0;
         this.hintNow = false;
         ZhNarratorClient.LOGGER.info("debug: quiz ask (speech) {} picture {}", q.id(), WordIcons.iconFor(q).map(s -> s.getItem().toString()).orElse("none"));
      }
   }

   public boolean canShoot() {
      return this.phrases.meta().shootDraw() >= 0 && this.phrases.meta().shootBang() >= 0;
   }

   public void startShooting(Minecraft mc) {
      this.shootAt = System.currentTimeMillis();
      this.killInTicks = 14;
      String shot = this.phrases.meta().shootSound();
      if (!shot.isEmpty()) {
         playMaster(mc, Identifier.fromNamespaceAndPath(this.phrases.meta().namespace(), shot), 1.0F, 1.0F);
      }
   }

   public int shootFrame(long now) {
      if (this.shootAt != 0L && this.canShoot()) {
         long t = now - this.shootAt;
         return t < 700L ? this.phrases.meta().shootDraw() : (t < 1300L ? this.phrases.meta().shootBang() : -1);
      } else {
         return -1;
      }
   }

   public boolean isAngry() {
      return System.currentTimeMillis() < this.angryUntil;
   }

   public boolean isFrowning() {
      return System.currentTimeMillis() < this.frownUntil;
   }

   @Nullable
   public Phrase pending() {
      return this.pending;
   }

   public float secondsLeft() {
      return (float)Math.max(0L, this.deadline - System.currentTimeMillis()) / 1000.0F;
   }

   public float fractionLeft() {
      return this.deadlineTotalMs <= 0L
         ? 0.0F
         : Math.max(0.0F, Math.min(1.0F, (float)(this.deadline - System.currentTimeMillis()) / (float)this.deadlineTotalMs));
   }

   public void debugAskChoice(Minecraft mc, String id) {
      this.phrases.fixed(id).ifPresent(q -> {
         this.pending = q;
         mc.gui.setScreen(new QuizScreen(this, q, this.options(q), 30.0F));
      });
   }

   public void suspend(Phrase question, List<Phrase> options, long remainingMs) {
      this.suspended = new QuizManager.Suspended(question, options, remainingMs);
   }

   public void answer(Minecraft mc, Phrase question, boolean correct) {
      this.pending = null;
      this.lastQuizAt = System.currentTimeMillis();
      long now = System.currentTimeMillis();
      this.lastQuizAt = now;
      boolean wasLearned = this.progress.isLearned(question.id());
      this.progress.answered(question, correct);
      if (correct && !wasLearned && this.progress.isLearned(question.id())) {
         boolean word = question.action().equals("hold");
         NarratorConfig cfg = ZhNarratorClient.config;
         if (cfg.learnedSound && cfg.learnedSoundVolume > 0.0F) {
            String verdict = cfg.learnedSoundStyle;
            switch (verdict) {
               case "fanfare":
                  playMaster(mc, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE.location(), cfg.learnedSoundVolume, 1.0F);
                  break;
               case "soft":
                  playMaster(mc, SoundEvents.UI_TOAST_IN.location(), cfg.learnedSoundVolume, 1.0F);
                  break;
               default:
                  playMaster(mc, SoundEvents.EXPERIENCE_ORB_PICKUP.location(), cfg.learnedSoundVolume, 1.0F);
                  this.secondDingAt = System.currentTimeMillis() + 160L;
            }
         }

         this.phrases.fixed(word ? "learned_word" : "learned_phrase").ifPresent(this.speaker::say);
         NarratorHud.showBanner(question, word, 5000L);
         chat(mc, "§6" + (word ? "New word learned: " : "New phrase learned: ") + "§e" + question.bare() + " §7— " + question.en().replaceAll("[.!]$", ""));
      }

      Consequences.onAnswer(mc, correct);
      this.speaker.sayNow(question);
      NarratorConfig cfg = ZhNarratorClient.config;
      if (correct) {
         this.wrongStreak = 0;
         NarratorHud.hop();
      } else {
         this.wrongStreak++;
         this.frownUntil = now + 2500L;
         if (cfg.angryAfterWrong > 0 && this.wrongStreak >= cfg.angryAfterWrong) {
            this.wrongStreak = 0;
            this.angryUntil = now + 8000L;
            Optional<Phrase> angry = this.phrases.fixed("angry");
            angry.ifPresent(this.speaker::say);
            Consequences.wipeInventory(mc);
            chat(mc, "§c" + angry.map(Phrase::text).orElse("") + " §7The teacher is angry: your inventory is gone.");
         }
      }

      if (mc.player != null) {
         LanguagePacks.Meta meta = this.phrases.meta();
         String verdict = correct ? "§a" + meta.uiCorrect() + " ✓ " : "§c" + meta.uiWrong() + " ✗ ";
         mc.player
            .sendSystemMessage(Component.literal(verdict + question.text() + (question.hasReading() ? "  " + question.reading() : "") + "  — " + question.en()));
      }

      ZhNarratorClient.LOGGER.info("quiz {} {} | {}", new Object[]{question.id(), correct ? "correct" : "wrong", question.text()});
   }

   private boolean calmMoment(LocalPlayer p, long now) {
      return this.waitReason(p, now) == null;
   }

   @Nullable
   public String waitReason(LocalPlayer p, long now) {
      if (p.isDeadOrDying()) {
         return "dead";
      } else if (now - this.lastDamageAt <= 5000L) {
         return "hurt";
      } else if (p.isSleeping()) {
         return "sleep";
      } else if (p.getVehicle() != null) {
         return "vehicle";
      } else if (p.isInWater()) {
         return "water";
      } else if (p.onGround() && !p.isFallFlying()) {
         Minecraft mc = Minecraft.getInstance();
         if ((mc.gameMode == null || !mc.gameMode.isDestroying()) && !mc.options.keyAttack.isDown() && !p.isUsingItem()) {
            if (now < this.busyUntil) {
               return "busy";
            } else {
               return now - this.lastActiveAt >= 20000L ? "afk" : null;
            }
         } else {
            this.busyUntil = now + 1500L;
            return "busy";
         }
      } else {
         return "air";
      }
   }

   public long nextQuestionInMs(long now) {
      return Math.max(0L, this.lastQuizAt + (long)(ZhNarratorClient.config.quizIntervalSeconds * 1000.0F) - now);
   }

   public boolean nothingToAsk() {
      return this.nothingToAsk;
   }

   public boolean questionOpen() {
      return this.pending != null || this.suspended != null;
   }

   public boolean helperMissing() {
      return ZhNarratorClient.config.quizMode.equals("speech") && !this.helperUp;
   }

   private static boolean speechable(Phrase p) {
      if (PhraseBook.isBareWord(p)) {
         return false;
      } else {
         String bare = p.bare();
         return ZhNarratorClient.phrases != null && ZhNarratorClient.phrases.meta().wordSeparator().isEmpty()
            ? bare.length() >= 3
            : bare.trim().split("\\s+").length >= 2;
      }
   }

   private List<QuizManager.Pool> pools(boolean forSpeech) {
      long now = System.currentTimeMillis();
      Progress.World world = this.progress.world();
      QuizManager.Pool fresh = new QuizManager.Pool(new ArrayList<>(), new ArrayList<>());
      QuizManager.Pool freshSpeech = new QuizManager.Pool(new ArrayList<>(), new ArrayList<>());
      List<Phrase> recent = new ArrayList<>();

      for (Phrase p : this.phrases.all()) {
         int heardTimes = world.heard.getOrDefault(p.id(), 0);
         if (!PhraseBook.isFragment(p) && heardTimes != 0 && this.progress.restLeft(p.id(), now) <= 0L) {
            if ((float)this.progress.sinceAskedMs(p.id(), now) < ZhNarratorClient.config.quizRetryAfterSeconds * 1000.0F) {
               recent.add(p);
            } else {
               int asked = this.progress.asked(p.id());
               int wrong = asked - this.progress.correct(p.id());
               double w = (1.0 + wrong * 2) / (1.0 + this.progress.correct(p.id())) * Math.min(heardTimes, 5);
               fresh.phrases().add(p);
               fresh.weights().add(w);
               if (speechable(p)) {
                  freshSpeech.phrases().add(p);
                  freshSpeech.weights().add(w);
               }
            }
         }
      }

      recent.sort(Comparator.<Phrase>comparingLong(px -> this.progress.sinceAskedMs(px.id(), now)).reversed());
      List<QuizManager.Pool> out = new ArrayList<>();
      if (forSpeech) {
         out.add(freshSpeech);
      }

      out.add(fresh);
      if (forSpeech) {
         recent.stream().filter(QuizManager::speechable).findFirst().ifPresent(px -> out.add(new QuizManager.Pool(List.of(px), List.of(1.0))));
      }

      if (!recent.isEmpty()) {
         out.add(new QuizManager.Pool(List.of(recent.getFirst()), List.of(1.0)));
      }

      return out;
   }

   private boolean hasCandidates() {
      return this.pools(false).stream().anyMatch(pool -> !pool.isEmpty());
   }

   @Nullable
   private Phrase pick(boolean forSpeech) {
      for (QuizManager.Pool pool : this.pools(forSpeech)) {
         if (!pool.isEmpty()) {
            return this.pickFrom(pool);
         }
      }

      return null;
   }

   private Phrase pickFrom(QuizManager.Pool pool) {
      List<Phrase> list = pool.phrases();
      List<Double> weights = pool.weights();
      List<Phrase> nouns = new ArrayList<>();
      List<Double> nounWeights = new ArrayList<>();

      for (int i = 0; i < list.size(); i++) {
         if (list.get(i).action().equals("hold")) {
            nouns.add(list.get(i));
            nounWeights.add(weights.get(i));
         }
      }

      if (!nouns.isEmpty() && nouns.size() < list.size() && this.random.nextDouble() < 0.4) {
         list = nouns;
         weights = nounWeights;
      }

      double total = 0.0;

      for (double w : weights) {
         total += w;
      }

      double r = this.random.nextDouble() * total;

      for (int ix = 0; ix < list.size(); ix++) {
         r -= weights.get(ix);
         if (r <= 0.0) {
            return list.get(ix);
         }
      }

      return list.getLast();
   }

   private List<Phrase> options(Phrase question) {
      List<Phrase> sameAction = new ArrayList<>(this.phrases.byAction(question.action()));
      sameAction.remove(question);
      Collections.shuffle(sameAction, this.random);
      List<Phrase> options = new ArrayList<>();
      options.add(question);

      for (Phrase p : sameAction) {
         if (options.size() == 4) {
            break;
         }

         if (!p.text().equals(question.text())) {
            options.add(p);
         }
      }

      if (options.size() < 4) {
         List<Phrase> any = new ArrayList<>(this.phrases.all());
         Collections.shuffle(any, this.random);

         for (Phrase p : any) {
            if (options.size() == 4) {
               break;
            }

            if (!PhraseBook.isFragment(p) && options.stream().noneMatch(o -> o.text().equals(p.text()))) {
               options.add(p);
            }
         }
      }

      Collections.shuffle(options, this.random);
      return options;
   }

   public static enum Phase {
      NONE,
      WAITING,
      RECORDING,
      CHECKING;
   }

   private record Pool(List<Phrase> phrases, List<Double> weights) {
      boolean isEmpty() {
         return this.phrases.isEmpty();
      }
   }

   private record Suspended(Phrase question, List<Phrase> options, long remainingMs) {
   }
}
