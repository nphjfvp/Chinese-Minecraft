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
               slider("zhnarrator.options.learned_after", 1.0, 10.0, 1.0, c.learnedAfterCorrect, "", v -> c.learnedAfterCorrect = Math.round(v)),
               cycle("zhnarrator.options.learned_mode", List.of("until_death", "rest"), c.learnedMode, v -> c.learnedMode = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.learned_rest", 10.0, 240.0, 10.0, c.learnedRestMinutes, " min", v -> c.learnedRestMinutes = v),
               OptionInstance.createBoolean("zhnarrator.options.mute_learned", c.muteLearned, v -> c.muteLearned = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.quiz_rest", 1.0, 30.0, 1.0, c.quizRepeatAfterMinutes, " min", v -> c.quizRepeatAfterMinutes = v),
               slider("zhnarrator.options.retry_after", 0.0, 600.0, 30.0, c.quizRetryAfterSeconds, " s", v -> c.quizRetryAfterSeconds = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.rest_after_correct", c.restAfterCorrect, v -> c.restAfterCorrect = v),
               OptionInstance.createBoolean("zhnarrator.options.quiz_reward", c.quizReward, v -> c.quizReward = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               OptionInstance.createBoolean("zhnarrator.options.quiz_pause", c.quizPauseGame, v -> c.quizPauseGame = v),
               OptionInstance.createBoolean("zhnarrator.options.quiz_icons", c.quizShowIcons, v -> c.quizShowIcons = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               slider("zhnarrator.options.angry_after", 0.0, 10.0, 1.0, c.angryAfterWrong, "", v -> c.angryAfterWrong = Math.round(v)),
               OptionInstance.createBoolean("zhnarrator.options.learned_sound", c.learnedSound, v -> c.learnedSound = v)
            }
         );
      this.list
         .addSmall(
            new OptionInstance[]{
               cycle("zhnarrator.options.learned_sound_style", List.of("xp", "fanfare", "soft"), c.learnedSoundStyle, v -> c.learnedSoundStyle = v),
               percent("zhnarrator.options.learned_sound_volume", c.learnedSoundVolume, v -> c.learnedSoundVolume = v)
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
