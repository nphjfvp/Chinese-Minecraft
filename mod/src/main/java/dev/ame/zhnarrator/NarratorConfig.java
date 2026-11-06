package dev.ame.zhnarrator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NarratorConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   public int configVersion = 4;
   public boolean enabled = true;
   public String language = "";
   public boolean languageHinted = false;
   public boolean voiceHinted = false;
   public boolean voiceOffered = false;
   public String voice = "";
   public String speed = "normal";
   public float volume = 1.0F;
   public float repeatSeconds = 3.0F;
   public float movementDelaySeconds = 1.5F;
   public float jumpCooldownSeconds = 4.0F;
   public float sameEventCooldownSeconds = 0.6F;
   public int maxQueue = 2;
   public boolean sayMovement = true;
   public boolean sayCombat = true;
   public boolean sayBlocks = true;
   public boolean sayItems = true;
   public boolean sayWorld = true;
   public boolean sayHeldItem = true;
   public boolean sayWeatherAndTime = true;
   public boolean sayClimate = true;
   public float climateRepeatSeconds = 10.0F;
   public boolean sayKills = true;
   public float weatherReportMinutes = 5.0F;
   public boolean sayDamage = true;
   public String quizMode = "choice";
   public String speechHelperUrl = "http://127.0.0.1:17645";
   public boolean voiceHelperAuto = false;
   public float speechPassScore = 0.5F;
   public String speechPinyinHint = "new";
   public String hintLanguage = "auto";
   public float quizRepeatAfterMinutes = 5.0F;
   public boolean restAfterCorrect = true;
   public int learnedAfterCorrect = 1;
   public float learnedRestMinutes = 60.0F;
   public boolean muteLearned = false;
   public String learnedMode = "until_death";
   public float quizRetryAfterSeconds = 120.0F;
   public boolean learnedSound = true;
   public String learnedSoundStyle = "xp";
   public float learnedSoundVolume = 0.5F;
   public float quizIntervalSeconds = 60.0F;
   public float quizAnswerSeconds = 12.0F;
   public String quizPenalty = "debuff";
   public boolean quizReward = true;
   public boolean quizPauseGame = false;
   public boolean quizShowIcons = true;
   public int angryAfterWrong = 3;
   public boolean hudEnabled = true;
   public String hudFont = "default";
   public float hudScale = 2.0F;
   public boolean hudShowEnglish = true;
   public boolean hudShowPinyin = true;
   public float hudHoldSeconds = 3.0F;
   public String hudAnchor = "bottom";
   public int hudOffset = 80;
   public float hudY = 0.72F;
   public int hudColorZh = -8054;
   public int hudColorPinyin = -5185281;
   public int hudColorEn = -2236963;
   public boolean hudShadow = true;
   public boolean hudColourGrammar = true;
   public int hudColorPronoun = -7419393;
   public int hudColorParticle = -4473925;
   public int hudColorVerb = -22195;
   public int hudColorAdjective = -746814;
   public int hudColorObject = -6560899;
   public int hudColorNumber = -3758849;
   public boolean hudHeldLabel = true;
   public boolean tooltipChinese = true;
   public String iconMode = "learned";
   public String hudPortrait = "bottom_right";
   public float hudPortraitScale = 1.5F;
   public String hudCounter = "top_left";
   public float hudCounterScale = 1.0F;
   public boolean hudQuizTimer = true;
   public boolean hudBackdrop = true;

   public boolean allows(String action) {
      return switch (action) {
         case "walk", "sprint", "sneak", "swim", "fly", "climb", "jump", "ride" -> this.sayMovement;
         case "hit", "attack", "kill", "kill_it", "hurt", "fall", "die", "burning" -> this.sayCombat;
         case "break", "break_hand", "place", "open", "close", "use" -> this.sayBlocks;
         case "hold", "drop", "pickup", "eat", "drink" -> this.sayItems;
         case "word" -> true;
         default -> this.sayWorld;
      };
   }

   public static NarratorConfig load(Path file) {
      if (Files.exists(file)) {
         try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            NarratorConfig c = (NarratorConfig)GSON.fromJson(r, NarratorConfig.class);
            if (c != null) {
               if (c.configVersion < 2) {
                  c.learnedAfterCorrect = 1;
                  c.learnedMode = "until_death";
                  c.angryAfterWrong = 3;
                  c.iconMode = "learned";
                  c.learnedSound = true;
                  c.quizShowIcons = true;
                  c.restAfterCorrect = true;
                  c.configVersion = 2;
               }

               if (c.configVersion < 3) {
                  if (c.hudFont == null || c.hudFont.equals("zhnarrator:kai")) {
                     c.hudFont = "default";
                  }

                  if (c.language == null || c.language.isEmpty()) {
                     c.language = "zh";
                  }

                  c.configVersion = 3;
               }

               if (c.configVersion < 4) {
                  if ("fanfare".equals(c.learnedSoundStyle)) {
                     c.learnedSoundStyle = "xp";
                  }

                  c.configVersion = 4;
               }

               c.save(file);
               return c;
            }
         } catch (RuntimeException | IOException var6) {
            ZhNarratorClient.LOGGER.warn("Could not read {}, using defaults: {}", file, var6.toString());
         }
      }

      NarratorConfig c = new NarratorConfig();
      c.save(file);
      return c;
   }

   public void save(Path file) {
      try {
         Files.createDirectories(file.getParent());

         try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
         }
      } catch (IOException var7) {
         ZhNarratorClient.LOGGER.warn("Could not write {}: {}", file, var7.toString());
      }
   }
}
