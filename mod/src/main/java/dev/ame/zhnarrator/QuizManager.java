package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
   /** The round being asked: up to four questions about one sentence. */
   @Nullable
   private QuizRound round;
   private int roundStep;
   /** False once one of questions 1–3 was answered wrong. */
   private boolean roundOk;
   private long nextStepAt;
   private int failedRounds;
   /** The microphone the helper last recorded from, set from its answer. */
   private volatile String micName = "";
   private String micAnnounced = "";
   private static final long BETWEEN_QUESTIONS_MS = 1500L;
   /** No question before this time: a mob came close or the player got hurt. */
   private long dangerUntil;
   /** Set from the server thread when the player took real damage (singleplayer). */
   private volatile boolean hurtPending;

   public QuizManager(PhraseBook phrases, Speaker speaker, Progress progress) {
      this.phrases = phrases;
      this.speaker = speaker;
      this.progress = progress;
   }

   public void onJoinedWorld() {
      long now = System.currentTimeMillis();
      this.lastQuizAt = now;
      this.lastTickAt = now;
      this.nothingToAsk = this.progress.heardForQuiz().isEmpty();
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
         } else if (gap > 0L) {
            this.progress.addPlayTime(gap);
         }

         boolean healthDropped = this.lastHealth >= 0.0F && player.getHealth() < this.lastHealth - 0.5F;
         if (healthDropped && mc.getSingleplayerServer() == null) {
            // On a server the cause is unknown here: any lost health counts as danger.
            this.hurtPending = true;
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
         String mic = this.micName;
         if (!mic.isEmpty() && !mic.equals(this.micAnnounced)) {
            this.micAnnounced = mic;
            chat(mc, "§7Microphone: " + mic + " (change it in the settings, I)");
         }
         NarratorConfig c = ZhNarratorClient.config;
         String danger = this.danger(mc, player, now);
         if (danger != null) {
            this.interrupt(mc, danger, now);
         }

         if (this.suspended != null) {
            if (mc.gui.screen() == null && now >= this.dangerUntil) {
               QuizManager.Suspended s = this.suspended;
               this.suspended = null;
               mc.gui.setScreen(new QuizScreen(this, s.question(), s.options(), (float)s.remainingMs() / 1000.0F, s.titleKey(), s.titleArg()));
            }
         } else if (this.pending != null && this.phase != QuizManager.Phase.NONE) {
            this.tickSpeech(mc, now);
         } else if (this.round != null) {
            if (player.isDeadOrDying()) {
               this.endRound(now);
            } else if (this.pending == null && now >= this.nextStepAt && now >= this.dangerUntil && mc.gui.screen() == null && this.safeToContinue(player, now)) {
               this.openStep(mc, now);
            }
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
            } else if (!((float)(now - this.lastQuizAt) < c.quizIntervalSeconds * 1000.0F) && now >= this.dangerUntil && this.calmMoment(player, now)) {
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
                     boolean withSpeech = !c.quizMode.equals("choice") && this.helperUp && speechable(question);
                     this.round = QuizRound.build(this.phrases, question, this.random, withSpeech, this.options(question));
                     this.roundStep = 0;
                     this.roundOk = true;
                     this.lastQuizAt = now;
                     this.ding(mc);
                     ZhNarratorClient.LOGGER
                        .info("quiz round {} | {} | {}", new Object[]{question.id(), question.en(), this.round.questions.stream().map(q -> q.step().name()).toList()});
                     this.openStep(mc, now);
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
               this.speech.start().thenAccept(device -> {
                  if (!device.isEmpty()) {
                     this.micName = device;
                  }
               });
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
      chat(mc, h != null && !h.silent() ? heardLine(h) : "§7(nothing heard" + micHint(h) + ")");
      if (h != null && !h.silent() && question != null && ZhNarratorClient.phrases != null && ZhNarratorClient.phrases.meta().language().equals("zh")) {
         this.progress.toneResult(question, h.toneErrors() == 0);
      }
      if (question != null) {
         this.answer(mc, question, ok);
      }
   }

   /** Which microphone recorded and how loud it got, to find out why nothing was heard. */
   private static String micHint(@Nullable SpeechClient.Heard h) {
      if (h == null || h.device().isEmpty()) {
         return "";
      } else {
         return " — microphone: " + h.device() + (h.peak() >= 0.0F ? ", loudest " + Math.round(h.peak() * 100.0F) + "%" : "");
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
         mc.gui.setScreen(new QuizScreen(this, q, this.options(q), 30.0F, "zhnarrator.quiz.prompt", ""));
      });
   }

   public void suspend(Phrase question, List<Phrase> options, long remainingMs, String titleKey, String titleArg) {
      this.suspended = new QuizManager.Suspended(question, options, remainingMs, titleKey, titleArg);
   }

   /** Shows the round's current question: a choice screen, or for the last step the spoken answer. */
   private void openStep(Minecraft mc, long now) {
      QuizRound.Question q = this.round.questions.get(this.roundStep);
      NarratorConfig c = ZhNarratorClient.config;
      if (q.step() == QuizRound.Step.SPEECH && !this.helperUp) {
         this.endRound(now);
      } else {
         this.pending = q.question();
         this.hintNow = false;
         if (q.step() == QuizRound.Step.SPEECH) {
            this.phase = QuizManager.Phase.WAITING;
            this.deadline = now + (long)(c.quizAnswerSeconds * 1500.0F);
            this.deadlineTotalMs = this.deadline - now;
            this.heard = null;
            this.heardError = null;
            this.bestHeard = null;
            this.attempts = 0;
            ZhNarratorClient.LOGGER.info("quiz ask (speech) {} | {}", q.question().id(), q.question().en());
         } else {
            ZhNarratorClient.LOGGER.info("quiz ask {} {} | {}", new Object[]{q.step(), q.question().id(), q.question().text()});
            mc.gui.setScreen(new QuizScreen(this, q.question(), q.options(), c.quizAnswerSeconds, q.titleKey(), q.titleArg()));
         }
      }
   }

   private void endRound(long now) {
      this.round = null;
      this.pending = null;
      this.phase = QuizManager.Phase.NONE;
      this.lastQuizAt = now;
   }

   /** One question of the round was answered (or ran out of time). */
   public void answer(Minecraft mc, Phrase question, boolean correct) {
      long now = System.currentTimeMillis();
      this.pending = null;
      this.lastQuizAt = now;
      QuizRound.Step step = this.round == null ? QuizRound.Step.SENTENCE : this.round.questions.get(this.roundStep).step();
      Phrase sentence = this.round == null ? question : this.round.sentence;
      // Every question gives one effect, and a right one earns back one stack from the pledge.
      Consequences.onAnswer(mc, correct);
      if (correct) {
         Consequences.returnPledged(mc);
      }

      if (step == QuizRound.Step.TONE) {
         this.progress.toneResult(sentence, correct);
      }

      // The beginning alone has no recording; the word has one when it is a thing (泥土), not a verb.
      if ((step == QuizRound.Step.WORD || step == QuizRound.Step.TONE) && this.round.word != null && this.round.word.key() != null) {
         this.speaker.sayNow(this.round.word);
      } else if (step != QuizRound.Step.FRAME) {
         this.speaker.sayNow(sentence);
      }

      if (correct) {
         NarratorHud.hop();
      } else {
         this.frownUntil = now + 2500L;
         if (step.countsForLearning()) {
            this.roundOk = false;
         }
      }

      if (mc.player != null) {
         LanguagePacks.Meta meta = this.phrases.meta();
         String verdict = correct ? "§a" + meta.uiCorrect() + " ✓ " : "§c" + meta.uiWrong() + " ✗ ";
         mc.player
            .sendSystemMessage(Component.literal(verdict + question.text() + (question.hasReading() ? "  " + question.reading() : "") + "  — " + question.en()));
      }

      ZhNarratorClient.LOGGER.info("quiz {} {} {} | {}", new Object[]{step, question.id(), correct ? "correct" : "wrong", question.text()});
      if (step == QuizRound.Step.SENTENCE) {
         this.finishLearning(mc, sentence, now);
      }

      this.roundStep++;
      if (this.round == null || this.roundStep >= this.round.questions.size()) {
         this.endRound(now);
      } else {
         this.nextStepAt = now + BETWEEN_QUESTIONS_MS;
      }
   }

   /**
    * After question 3: the round counts for learning when questions 1–3 were all right, and two such
    * rounds in a row master the word. Failed rounds in a row make the teacher angry.
    */
   private void finishLearning(Minecraft mc, Phrase sentence, long now) {
      NarratorConfig cfg = ZhNarratorClient.config;
      boolean mastered = this.progress.answered(sentence, this.roundOk);
      if (mastered) {
         boolean word = sentence.action().equals("hold");
         if (cfg.learnedSound && cfg.learnedSoundVolume > 0.0F) {
            String style = cfg.learnedSoundStyle;
            switch (style) {
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

         this.phrases.fixed(word ? "learned_word" : "learned_phrase").ifPresent(this.speaker::sayPriority);
         NarratorHud.showBanner(sentence, word, 5000L);
         chat(mc, "§6" + (word ? "New word learned: " : "New phrase learned: ") + "§e" + sentence.bare() + " §7— " + sentence.en().replaceAll("[.!]$", ""));
      }

      if (this.roundOk) {
         this.failedRounds = 0;
      } else {
         this.failedRounds++;
         if (cfg.angryAfterFailedRounds > 0 && this.failedRounds >= cfg.angryAfterFailedRounds) {
            this.failedRounds = 0;
            this.angryUntil = now + 8000L;
            Optional<Phrase> angry = this.phrases.fixed("angry");
            angry.ifPresent(this.speaker::sayPriority);
            Consequences.wipeInventory(mc);
            chat(mc, "§c" + angry.map(Phrase::text).orElse("") + " §7The teacher is angry: your inventory went to the pledge. Every right answer gives one stack back.");
         }
      }
   }

   private boolean calmMoment(LocalPlayer p, long now) {
      return this.waitReason(p, now) == null;
   }

   /** The next question of a running round: like a calm moment, but standing still while answering is fine. */
   private boolean safeToContinue(LocalPlayer p, long now) {
      String reason = this.waitReason(p, now);
      return reason == null || reason.equals("afk");
   }

   /** Called on the server thread when the local player took damage that is not from the quiz's own effects. */
   public void onHurt() {
      this.hurtPending = true;
   }

   /** Why the quiz has to stop right now, or null: the player got hurt, or a hostile mob is close. */
   @Nullable
   private String danger(Minecraft mc, LocalPlayer player, long now) {
      if (this.hurtPending) {
         this.hurtPending = false;
         this.lastDamageAt = now;
         return "you got hurt";
      } else {
         float reach = ZhNarratorClient.config.dangerDistance;
         if (reach > 0.0F && mc.level != null) {
            for (net.minecraft.world.entity.LivingEntity e : mc.level
               .getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(reach), x -> x instanceof net.minecraft.world.entity.monster.Enemy && x.isAlive())) {
               if (e.distanceTo(player) <= reach) {
                  return e.getName().getString() + " nearby";
               }
            }
         }

         return null;
      }
   }

   /**
    * Stops the quiz for a while: an open question closes without counting (it comes again later, with
    * its full time), and no question opens for {@code dangerPauseSeconds}. While the danger lasts, the
    * pause keeps starting over.
    */
   private void interrupt(Minecraft mc, String why, long now) {
      boolean asking = this.pending != null || this.suspended != null || mc.gui.screen() instanceof QuizScreen;
      long pause = (long)(ZhNarratorClient.config.dangerPauseSeconds * 1000.0F);
      this.dangerUntil = Math.max(this.dangerUntil, now + pause);
      if (asking) {
         if (this.phase == QuizManager.Phase.RECORDING) {
            this.speech.stop(null, 0.0F).exceptionally(e -> null);
         }

         this.phase = QuizManager.Phase.NONE;
         this.pending = null;
         this.suspended = null;
         this.heard = null;
         this.heardError = null;
         this.bestHeard = null;
         if (mc.gui.screen() instanceof QuizScreen) {
            mc.gui.setScreen(null);
         }

         if (this.round == null) {
            this.lastQuizAt = now;
         }

         ZhNarratorClient.LOGGER.info("quiz paused: {}", why);
         chat(mc, "§e⚠ Quiz paused: " + why + ". §7Get to safety; it goes on " + Math.round(pause / 1000.0F) + " s after the danger is gone.");
      }
   }

   /** How long the quiz still waits after danger, in ms; 0 when it does not. */
   public long dangerLeftMs(long now) {
      return Math.max(0L, this.dangerUntil - now);
   }

   @Nullable
   public String waitReason(LocalPlayer p, long now) {
      if (p.isDeadOrDying()) {
         return "dead";
      } else if (now < this.dangerUntil || now - this.lastDamageAt <= 5000L) {
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
      return this.pending != null || this.suspended != null || this.round != null;
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

   /** Phrases that can be asked in this world (heard here, whole sentences), grouped by word. */
   private Map<String, List<Phrase>> askableByCard() {
      Map<String, Integer> heard = this.progress.heardForQuiz();
      Map<String, List<Phrase>> byCard = new HashMap<>();

      for (Phrase p : this.phrases.all()) {
         if (!PhraseBook.isFragment(p) && heard.getOrDefault(p.id(), 0) > 0) {
            byCard.computeIfAbsent(Progress.cardKey(p), k -> new ArrayList<>()).add(p);
         }
      }

      return byCard;
   }

   /** Adds new words to learn until the configured number is reached, and tells the player about them. */
   private void fillActive(Map<String, List<Phrase>> askable) {
      Map<String, Integer> heardMap = this.progress.heardForQuiz();
      Map<String, Integer> heardByCard = new HashMap<>();
      askable.forEach((key, list) -> {
         int heard = 0;

         for (Phrase p : list) {
            heard += heardMap.getOrDefault(p.id(), 0);
         }

         heardByCard.put(key, heard);
      });

      for (String key : this.progress.fillActive(heardByCard, Math.max(1, ZhNarratorClient.config.activeWords))) {
         Phrase shown = this.phrases.fixed("hold." + key).orElse(askable.get(key).getFirst());
         ZhNarratorClient.LOGGER.info("now learning {} ({})", key, shown.text());
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.player != null) {
            chat(mc, "§b★ New word to learn: §e" + shown.labelWithReading() + " §7— " + shown.en().replaceAll("[.!]$", ""));
         }
      }
   }

   /** Least recently asked phrase of a word, in ms; huge if none was asked yet. */
   private long sinceAskedCard(List<Phrase> list, long now) {
      long since = Long.MAX_VALUE;

      for (Phrase p : list) {
         since = Math.min(since, this.progress.sinceAskedMs(p.id(), now));
      }

      return since;
   }

   private boolean hasCandidates() {
      return this.pick(false) != null;
   }

   /**
    * Picks the next question. A review of a mastered word that is due comes first, the most overdue
    * one. Otherwise one of the words being learned is asked, ones with fewer correct answers in a row
    * more often, skipping words asked in the last {@code quizRetryAfterSeconds} when possible.
    */
   @Nullable
   private Phrase pick(boolean forSpeech) {
      long now = System.currentTimeMillis();
      Map<String, List<Phrase>> askable = this.askableByCard();
      if (askable.isEmpty()) {
         return null;
      } else {
         this.fillActive(askable);
         long retryMs = (long)(ZhNarratorClient.config.quizRetryAfterSeconds * 1000.0F);
         String due = null;
         long dueAt = Long.MAX_VALUE;
         List<String> active = new ArrayList<>();
         List<Double> weights = new ArrayList<>();
         String stalest = null;
         long stalestSince = -1L;

         for (Map.Entry<String, List<Phrase>> e : askable.entrySet()) {
            Progress.Card card = this.progress.card(e.getKey());
            if (card != null) {
               if (card.mastered) {
                  if (card.dueAt <= this.progress.playMs && card.dueAt < dueAt) {
                     due = e.getKey();
                     dueAt = card.dueAt;
                  }
               } else {
                  long since = this.sinceAskedCard(e.getValue(), now);
                  if (since > stalestSince) {
                     stalest = e.getKey();
                     stalestSince = since;
                  }

                  if (since >= retryMs) {
                     active.add(e.getKey());
                     weights.add((1.0 + 0.5 * card.toneTrouble) / (1.0 + card.streak));
                  }
               }
            }
         }

         String chosen;
         if (due != null) {
            chosen = due;
         } else if (!active.isEmpty()) {
            chosen = active.getLast();
            double r = this.random.nextDouble() * weights.stream().mapToDouble(Double::doubleValue).sum();

            for (int i = 0; i < active.size(); i++) {
               r -= weights.get(i);
               if (r <= 0.0) {
                  chosen = active.get(i);
                  break;
               }
            }
         } else {
            chosen = stalest;
         }

         return chosen == null ? null : this.pickPhrase(askable.get(chosen), forSpeech, now);
      }
   }

   /** Picks which sentence about the chosen word to ask: rarely asked and often wrong ones first. */
   private Phrase pickPhrase(List<Phrase> list, boolean forSpeech, long now) {
      Progress.World world = this.progress.world();
      long retryMs = (long)(ZhNarratorClient.config.quizRetryAfterSeconds * 1000.0F);
      QuizManager.Pool fresh = new QuizManager.Pool(new ArrayList<>(), new ArrayList<>());
      QuizManager.Pool freshSpeech = new QuizManager.Pool(new ArrayList<>(), new ArrayList<>());
      QuizManager.Pool all = new QuizManager.Pool(new ArrayList<>(), new ArrayList<>());

      for (Phrase p : list) {
         int asked = this.progress.asked(p.id());
         int wrong = asked - this.progress.correct(p.id());
         double w = (1.0 + wrong * 2) / (1.0 + this.progress.correct(p.id())) * Math.min(this.progress.heardForQuiz().getOrDefault(p.id(), 1), 5);
         all.phrases().add(p);
         all.weights().add(w);
         if (this.progress.sinceAskedMs(p.id(), now) >= retryMs) {
            fresh.phrases().add(p);
            fresh.weights().add(w);
            if (speechable(p)) {
               freshSpeech.phrases().add(p);
               freshSpeech.weights().add(w);
            }
         }
      }

      if (forSpeech && !freshSpeech.isEmpty()) {
         return this.pickFrom(freshSpeech);
      } else {
         return this.pickFrom(fresh.isEmpty() ? all : fresh);
      }
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

   private record Suspended(Phrase question, List<Phrase> options, long remainingMs, String titleKey, String titleArg) {
   }
}
