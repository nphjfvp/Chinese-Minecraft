package dev.ame.zhnarrator;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

public final class Speaker {
   private static final long MAX_PHRASE_MS = 5000L;
   private static final long GAP_MS = 250L;
   private final ArrayDeque<Phrase> pendingSounds = new ArrayDeque<>();
   private static final long INTRA_GAP_MS = 60L;
   private static final long JOIN_MS = 40L;
   /** A sentence waiting longer than this is no longer about what the player is doing: it is dropped. */
   private static final long FRESH_MS = 2500L;
   /** How many recent events wait for the next free slot. */
   private static final int MAX_WAITING = 4;
   private final PhraseBook phrases;
   private final ArrayDeque<Speaker.Queued> queue = new ArrayDeque<>();
   private final Map<String, Long> lastSaid = new HashMap<>();
   /** The sentences said lately, newest first, for the sentence history screen. */
   private final ArrayDeque<Phrase> history = new ArrayDeque<>();
   private static final int HISTORY = 40;
   @Nullable
   private SoundInstance playing;
   @Nullable
   private Phrase shown;
   private long startedAt;
   private String clipKey = "";
   private long clipStartedAt;
   private static final long LIPSYNC_DELAY_MS = 50L;
   private long finishedAt;

   public Speaker(PhraseBook phrases) {
      this.phrases = phrases;
   }

   public void say(Phrase p) {
      this.enqueue(p, false);
   }

   public void saySequence(Phrase display, List<Phrase> sounds) {
      NarratorConfig c = ZhNarratorClient.config;
      if (c.enabled && c.allows(display.action()) && !sounds.isEmpty()) {
         long now = System.currentTimeMillis();
         Phrase noun = sounds.getLast();
         Long last = this.lastSaid.get(noun.id());
         if (last == null || !((float)(now - last) < c.sameEventCooldownSeconds * 1000.0F)) {
            this.addWaiting(new Speaker.Queued(display, false, false, sounds, now));
            this.lastSaid.put(noun.id(), now);
         }
      }
   }

   public void sayOngoing(Phrase p, boolean repeat) {
      this.queue.removeIf(Speaker.Queued::repeat);
      this.enqueue(p, repeat);
   }

   public void dropQueued(String id) {
      this.queue.removeIf(q -> q.repeat() && q.phrase().id().equals(id));
   }

   public void sayNow(Phrase p) {
      if (ZhNarratorClient.config.enabled) {
         this.stop();
         this.queue.addLast(new Speaker.Queued(p, false, true));
      }
   }

   /** Says this right after the current sentence, ignoring the pause between sentences (quiz feedback). */
   public void sayPriority(Phrase p) {
      if (ZhNarratorClient.config.enabled) {
         this.queue.addLast(new Speaker.Queued(p, false, true));
      }
   }

   public void stop() {
      if (this.playing != null) {
         Minecraft.getInstance().getSoundManager().stop(this.playing);
         this.playing = null;
         this.finishedAt = System.currentTimeMillis();
      }

      this.pendingSounds.clear();
      this.queue.clear();
   }

   private void enqueue(Phrase p, boolean repeat) {
      NarratorConfig c = ZhNarratorClient.config;
      if (c.enabled && c.allows(p.action())) {
         long now = System.currentTimeMillis();
         Long last = this.lastSaid.get(p.id());
         if (last == null || !((float)(now - last) < c.sameEventCooldownSeconds * 1000.0F)) {
            this.queue.removeIf(q -> !q.priority() && q.phrase().id().equals(p.id()));
            this.addWaiting(new Speaker.Queued(p, repeat, false, List.of(p), now));
            this.lastSaid.put(p.id(), now);
         }
      }
   }

   private void addWaiting(Speaker.Queued q) {
      this.queue.addLast(q);

      while (this.queue.stream().filter(x -> !x.priority()).count() > MAX_WAITING) {
         this.queue.stream().filter(x -> !x.priority()).findFirst().ifPresent(this.queue::remove);
      }
   }

   /**
    * The next sentence to say, or null. Quiz feedback goes first. Narration waits for the pause
    * between sentences; of the events that happened in the last {@link #FRESH_MS} the one heard least
    * often in this world wins, the newest on a tie, so that over time everything gets said.
    */
   @Nullable
   private Speaker.Queued nextQueued(long now) {
      for (Speaker.Queued q : this.queue) {
         if (q.priority()) {
            this.queue.remove(q);
            return q;
         }
      }

      this.queue.removeIf(q -> now - q.at() > FRESH_MS);
      if (this.queue.isEmpty() || (float)(now - this.startedAt) < ZhNarratorClient.config.speechGapSeconds * 1000.0F) {
         return null;
      } else {
         Map<String, Integer> heard = ZhNarratorClient.progress.world().heard;
         Speaker.Queued best = null;
         int bestHeard = Integer.MAX_VALUE;

         for (Speaker.Queued q : this.queue) {
            int times = heard.getOrDefault(q.sounds().getFirst().id(), 0);
            if (times <= bestHeard) {
               best = q;
               bestHeard = times;
            }
         }

         this.queue.clear();
         return best;
      }
   }

   public void tick(Minecraft mc) {
      long now = System.currentTimeMillis();
      if (this.playing != null && !this.pendingSounds.isEmpty()) {
         long spoken = this.phrases.spokenMs(this.clipKey);
         if (spoken >= 0L && now - this.clipStartedAt >= 50L + spoken + 40L) {
            this.playClip(mc, this.pendingSounds.pollFirst(), now);
            return;
         }
      }

      if (this.playing != null && (!mc.getSoundManager().isActive(this.playing) || now - this.startedAt > 5000L)) {
         this.playing = null;
         this.finishedAt = now;
      }

      QuizManager.Phase phase = ZhNarratorClient.quiz == null ? QuizManager.Phase.NONE : ZhNarratorClient.quiz.phase();
      boolean questionOpen = phase == QuizManager.Phase.RECORDING || phase == QuizManager.Phase.CHECKING;
      if (this.playing == null && !this.pendingSounds.isEmpty() && now - this.finishedAt >= 60L) {
         this.playClip(mc, this.pendingSounds.pollFirst(), now);
      } else {
         Speaker.Queued q;
         if (this.playing == null
            && !questionOpen
            && !this.queue.isEmpty()
            && now - Math.max(this.startedAt, this.finishedAt) >= 250L
            && (q = this.nextQueued(now)) != null) {
            this.shown = q.phrase();
            this.remember(q.phrase());
            this.startedAt = now;
            this.pendingSounds.clear();
            this.pendingSounds.addAll(q.sounds());
            this.playClip(mc, this.pendingSounds.pollFirst(), now);
            if (q.sounds().size() == 1) {
               ZhNarratorClient.LOGGER.info("say {} | {} | {}", new Object[]{q.phrase().id(), q.phrase().text(), q.phrase().en()});
            } else {
               ZhNarratorClient.LOGGER
                  .info("say sequence {} | {} | {}", new Object[]{q.sounds().stream().map(Phrase::id).toList(), q.phrase().text(), q.phrase().en()});
            }
         }
      }
   }

   private void playClip(Minecraft mc, Phrase clip, long now) {
      NarratorConfig c = ZhNarratorClient.config;
      Identifier sound = Identifier.fromNamespaceAndPath(this.phrases.namespace(), clip.soundPath(this.voice(), this.speed()));
      if (mc.getSoundManager().getSoundEvent(sound) == null) {
         ZhNarratorClient.LOGGER.warn("No audio for {} (looked for {}), showing text only", clip.id(), sound);
         this.finishedAt = now;
      } else {
         SimpleSoundInstance instance = new SimpleSoundInstance(
            sound, SoundSource.VOICE, c.volume, 1.0F, RandomSource.create(), false, 0, Attenuation.NONE, 0.0, 0.0, 0.0, true
         );
         mc.getSoundManager().play(instance);
         this.playing = instance;
         this.finishedAt = 0L;
         this.clipKey = clip.soundPath(this.voice(), this.speed());
         this.clipStartedAt = now;
      }

      this.lastSaid.put(clip.id(), now);
      if (clip.key() != null || !clip.action().endsWith("count")) {
         ZhNarratorClient.progress.heard(clip.id());
      }
   }

   private void remember(Phrase p) {
      if (this.history.isEmpty() || !this.history.peekFirst().text().equals(p.text())) {
         this.history.addFirst(p);

         while (this.history.size() > HISTORY) {
            this.history.removeLast();
         }
      }
   }

   /** The sentences said lately, newest first. */
   public List<Phrase> history() {
      return List.copyOf(this.history);
   }

   public boolean isSpeaking() {
      return this.playing != null || !this.pendingSounds.isEmpty();
   }

   public int mouth(long now) {
      if (this.playing == null) {
         return 0;
      } else {
         int level = this.phrases.mouth(this.clipKey, now - this.clipStartedAt - 50L);
         return level >= 0 ? level : (now / 120L % 2L == 0L ? 1 : 2);
      }
   }

   @Nullable
   public Phrase shown() {
      if (this.shown == null) {
         return null;
      } else {
         return this.pendingSounds.isEmpty()
               && this.finishedAt != 0L
               && (float)(System.currentTimeMillis() - this.finishedAt) > ZhNarratorClient.config.hudHoldSeconds * 1000.0F
            ? null
            : this.shown;
      }
   }

   public float visibility() {
      if (this.finishedAt != 0L && this.pendingSounds.isEmpty()) {
         float hold = Math.max(0.1F, ZhNarratorClient.config.hudHoldSeconds) * 1000.0F;
         return 1.0F - Math.min(1.0F, (float)(System.currentTimeMillis() - this.finishedAt) / hold);
      } else {
         return 1.0F;
      }
   }

   public String voice() {
      return pick(ZhNarratorClient.config.voice, this.phrases.voices());
   }

   public String speed() {
      return pick(ZhNarratorClient.config.speed, this.phrases.speeds());
   }

   public void cycleSpeed() {
      List<String> speeds = this.phrases.speeds();
      int i = speeds.indexOf(this.speed());
      ZhNarratorClient.config.speed = speeds.get((i + 1) % speeds.size());
   }

   private static String pick(String wanted, List<String> available) {
      return !available.contains(wanted) && !available.isEmpty() ? available.getFirst() : wanted;
   }

   private record Queued(Phrase phrase, boolean repeat, boolean priority, List<Phrase> sounds, long at) {
      Queued(Phrase phrase, boolean repeat, boolean priority) {
         this(phrase, repeat, priority, List.of(phrase), System.currentTimeMillis());
      }
   }
}
