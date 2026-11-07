package dev.ame.zhnarrator;

import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class NarratorAdvancedScreen extends NarratorSettingsScreen {
   public NarratorAdvancedScreen(Screen parent) {
      super(parent, Component.translatable("zhnarrator.options.advanced_title"));
   }

   @Override
   protected void addOptions() {
      NarratorConfig c = ZhNarratorClient.config;
      boolean iconWords = ZhNarratorClient.phrases != null && ZhNarratorClient.phrases.meta().iconWords();
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.movement_delay", 0.0, 5.0, 0.5, c.movementDelaySeconds, " s", v -> c.movementDelaySeconds = v),
               slider("zhnarrator.options.climate_repeat", 0.0, 60.0, 5.0, c.climateRepeatSeconds, " s", v -> c.climateRepeatSeconds = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.weather_report", 0.0, 30.0, 1.0, c.weatherReportMinutes, " min", v -> c.weatherReportMinutes = v),
               cycle("zhnarrator.options.position", List.of("bottom", "top"), c.hudAnchor, v -> c.hudAnchor = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.say_movement", c.sayMovement, v -> c.sayMovement = v),
               OptionInstance.createBoolean("zhnarrator.options.say_combat", c.sayCombat, v -> c.sayCombat = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.say_blocks", c.sayBlocks, v -> c.sayBlocks = v),
               OptionInstance.createBoolean("zhnarrator.options.say_items", c.sayItems, v -> c.sayItems = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.say_world", c.sayWorld, v -> c.sayWorld = v),
               OptionInstance.createBoolean("zhnarrator.options.say_held", c.sayHeldItem, v -> c.sayHeldItem = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.say_kills", c.sayKills, v -> c.sayKills = v),
               OptionInstance.createBoolean("zhnarrator.options.say_damage", c.sayDamage, v -> c.sayDamage = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.master_after", 1.0, 5.0, 1.0, c.masterAfterCorrect, "", v -> c.masterAfterCorrect = Math.round(v)),
               slider("zhnarrator.options.retry_after", 0.0, 600.0, 30.0, c.quizRetryAfterSeconds, " s", v -> c.quizRetryAfterSeconds = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.danger_distance", 0.0, 16.0, 1.0, c.dangerDistance, "", v -> c.dangerDistance = v),
               slider("zhnarrator.options.danger_pause", 0.0, 30.0, 1.0, c.dangerPauseSeconds, " s", v -> c.dangerPauseSeconds = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.quiz_listen", c.quizListen, v -> c.quizListen = v),
               OptionInstance.createBoolean("zhnarrator.options.quiz_tones", c.quizTones, v -> c.quizTones = v)
            }
         );
      this.list.addBig(OptionInstance.createBoolean("zhnarrator.options.speaking_stages", c.speakingStages, v -> c.speakingStages = v));
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.pinyin_hide", 0.0, 6.0, 1.0, c.pinyinHideAfterReviews, "", v -> c.pinyinHideAfterReviews = Math.round(v)),
               OptionInstance.createBoolean("zhnarrator.options.fullbright", c.fullbright, v -> c.fullbright = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.quiz_reward", c.quizReward, v -> c.quizReward = v),
               OptionInstance.createBoolean("zhnarrator.options.quiz_pause", c.quizPauseGame, v -> c.quizPauseGame = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.quiz_icons", c.quizShowIcons, v -> c.quizShowIcons = v),
               slider("zhnarrator.options.angry_after_rounds", 0.0, 10.0, 1.0, c.angryAfterFailedRounds, "", v -> c.angryAfterFailedRounds = Math.round(v))
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.learned_sound", c.learnedSound, v -> c.learnedSound = v),
               cycle("zhnarrator.options.learned_sound_style", List.of("xp", "fanfare", "soft"), c.learnedSoundStyle, v -> c.learnedSoundStyle = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               percent("zhnarrator.options.learned_sound_volume", c.learnedSoundVolume, v -> c.learnedSoundVolume = v),
               OptionInstance.createBoolean("zhnarrator.options.pledge_on_death", c.pledgeOnDeath, v -> c.pledgeOnDeath = v)
            }
         );
      if (iconWords) {
         this.list
            .addSmall(
               new OptionInstance[]{
                  cycle("zhnarrator.options.icon_mode", List.of("learned", "known", "off"), c.iconMode, v -> c.iconMode = v),
                  OptionInstance.createBoolean("zhnarrator.options.tooltip", c.tooltipChinese, v -> c.tooltipChinese = v)
               }
            );
      } else {
         this.list.addBig(OptionInstance.createBoolean("zhnarrator.options.tooltip", c.tooltipChinese, v -> c.tooltipChinese = v));
      }

      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.quiz_timer", c.hudQuizTimer, v -> c.hudQuizTimer = v),
               OptionInstance.createBoolean("zhnarrator.options.backdrop", c.hudBackdrop, v -> c.hudBackdrop = v)
            }
         );
   }
}
