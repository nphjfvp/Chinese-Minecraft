package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;

final class Consequences {
   private static final Identifier LOST_HEARTS = Identifier.fromNamespaceAndPath("zhnarrator", "lost_hearts");
   private static final int EFFECTS_PER_ANSWER = 2;
   private static final List<Consequences.Effect> DEBUFFS = List.of(
      new Consequences.Effect(MobEffects.SLOWNESS, 20, 5),
      new Consequences.Effect(MobEffects.WEAKNESS, 30, 3),
      new Consequences.Effect(MobEffects.MINING_FATIGUE, 30, 3),
      new Consequences.Effect(MobEffects.NAUSEA, 30, 1),
      new Consequences.Effect(MobEffects.BLINDNESS, 15, 1),
      new Consequences.Effect(MobEffects.DARKNESS, 20, 1),
      new Consequences.Effect(MobEffects.HUNGER, 40, 3),
      new Consequences.Effect(MobEffects.POISON, 10, 2),
      new Consequences.Effect(MobEffects.WITHER, 6, 1),
      new Consequences.Effect(MobEffects.LEVITATION, 5, 1),
      new Consequences.Effect(MobEffects.GLOWING, 120, 1),
      new Consequences.Effect(MobEffects.UNLUCK, 300, 3),
      new Consequences.Effect(MobEffects.INFESTED, 90, 1),
      new Consequences.Effect(MobEffects.OOZING, 90, 1),
      new Consequences.Effect(MobEffects.WEAVING, 90, 1),
      new Consequences.Effect(MobEffects.WIND_CHARGED, 90, 1)
   );
   private static final List<Consequences.Effect> BUFFS = List.of(
      new Consequences.Effect(MobEffects.REGENERATION, 15, 3),
      new Consequences.Effect(MobEffects.SPEED, 60, 3),
      new Consequences.Effect(MobEffects.JUMP_BOOST, 60, 3),
      new Consequences.Effect(MobEffects.HASTE, 60, 3),
      new Consequences.Effect(MobEffects.STRENGTH, 60, 2),
      new Consequences.Effect(MobEffects.RESISTANCE, 60, 2),
      new Consequences.Effect(MobEffects.FIRE_RESISTANCE, 120, 1),
      new Consequences.Effect(MobEffects.ABSORPTION, 60, 4),
      new Consequences.Effect(MobEffects.HEALTH_BOOST, 90, 4),
      new Consequences.Effect(MobEffects.SATURATION, 5, 1),
      new Consequences.Effect(MobEffects.NIGHT_VISION, 120, 1),
      new Consequences.Effect(MobEffects.WATER_BREATHING, 120, 1),
      new Consequences.Effect(MobEffects.SLOW_FALLING, 60, 1),
      new Consequences.Effect(MobEffects.INVISIBILITY, 60, 1),
      new Consequences.Effect(MobEffects.LUCK, 300, 3),
      new Consequences.Effect(MobEffects.HERO_OF_THE_VILLAGE, 300, 1),
      new Consequences.Effect(MobEffects.DOLPHINS_GRACE, 60, 1),
      new Consequences.Effect(MobEffects.CONDUIT_POWER, 60, 1)
   );

   private Consequences() {
   }

   static void kill(Minecraft mc) {
      IntegratedServer server = mc.getSingleplayerServer();
      if (server != null && mc.player != null) {
         UUID uuid = mc.player.getUUID();
         server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
               p.kill(p.level());
            }
         });
      }
   }

   static void onAnswer(Minecraft mc, boolean correct) {
      IntegratedServer server = mc.getSingleplayerServer();
      if (server != null && mc.player != null) {
         NarratorConfig c = ZhNarratorClient.config;
         Progress progress = ZhNarratorClient.progress;
         UUID uuid = mc.player.getUUID();
         String penalty = mc.level != null && mc.level.getLevelData().isHardcore() ? "hardcore" : c.quizPenalty;
         int heartsLost = progress.heartsLost();
         if (!correct && penalty.equals("hearts")) {
            progress.setHeartsLost(++heartsLost);
         }

         int lost = heartsLost;
         if (!correct && penalty.equals("hardcore") && ZhNarratorClient.quiz != null && ZhNarratorClient.quiz.canShoot()) {
            ZhNarratorClient.quiz.startShooting(mc);
         } else {
            server.execute(() -> {
               ServerPlayer p = server.getPlayerList().getPlayer(uuid);
               if (p != null) {
                  if (correct) {
                     if (c.quizReward) {
                        apply(p, BUFFS);
                     }
                  } else {
                     switch (penalty) {
                        case "hardcore":
                           p.kill(p.level());
                           break;
                        case "hearts":
                           AttributeInstance maxHealth = p.getAttribute(Attributes.MAX_HEALTH);
                           if (maxHealth == null) {
                              return;
                           }

                           maxHealth.addOrReplacePermanentModifier(new AttributeModifier(LOST_HEARTS, -2.0 * lost, Operation.ADD_VALUE));
                           if (maxHealth.getValue() <= 0.5) {
                              p.kill(p.level());
                           } else if (p.getHealth() > maxHealth.getValue()) {
                              p.setHealth((float)maxHealth.getValue());
                           }
                           break;
                        case "debuff":
                           apply(p, DEBUFFS);
                     }
                  }
               }
            });
         }
      } else {
         ZhNarratorClient.LOGGER.warn("Quiz rewards and penalties only work in singleplayer for now");
      }
   }

   static void wipeInventory(Minecraft mc) {
      IntegratedServer server = mc.getSingleplayerServer();
      if (server != null && mc.player != null) {
         UUID uuid = mc.player.getUUID();
         server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
               p.getInventory().clearContent();
               p.containerMenu.broadcastChanges();
            }
         });
      }
   }

   private static void apply(ServerPlayer p, List<Consequences.Effect> pool) {
      List<Consequences.Effect> picks = new ArrayList<>(pool);
      Collections.shuffle(picks);
      StringBuilder names = new StringBuilder();

      for (Consequences.Effect e : picks.subList(0, Math.min(2, picks.size()))) {
         p.addEffect(new MobEffectInstance(e.effect(), e.seconds() * 20, e.level() - 1));
         names.append(names.isEmpty() ? "" : ", ").append(((MobEffect)e.effect().value()).getDisplayName().getString()).append(" ").append(e.level());
      }

      ZhNarratorClient.LOGGER.info("effects: {}", names);
   }

   private record Effect(Holder<MobEffect> effect, int seconds, int level) {
   }
}
