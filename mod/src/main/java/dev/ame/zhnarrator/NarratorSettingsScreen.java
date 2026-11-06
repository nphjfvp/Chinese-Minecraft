package dev.ame.zhnarrator;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.OptionInstance.Enum;
import net.minecraft.client.OptionInstance.IntRange;
import net.minecraft.client.OptionInstance.UnitDouble;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class NarratorSettingsScreen extends OptionsSubScreen {
   @Nullable
   private final Screen parent;

   public NarratorSettingsScreen(@Nullable Screen parent) {
      this(parent, Component.translatable("zhnarrator.options.title"));
   }

   protected NarratorSettingsScreen(@Nullable Screen parent, Component title) {
      super(parent, Minecraft.getInstance().options, title);
      this.parent = parent;
   }

   protected void addOptions() {
      NarratorConfig c = ZhNarratorClient.config;
      PhraseBook phrases = ZhNarratorClient.phrases;
      LanguagePacks packs = ZhNarratorClient.packs;
      if (phrases == null) {
         this.list.addBig(Button.builder(Component.translatable("zhnarrator.no_pack"), b -> {}).width(310).build());
      } else {
         List<String> languages = packs.languages();
         this.list
            .addSmall(
               new OptionInstance[]{
                  OptionInstance.createBoolean("zhnarrator.options.enabled", c.enabled, v -> c.enabled = v),
                  this.languageOption(languages, phrases.meta().language())
               }
            );
         if (!phrases.hintLanguages().isEmpty()) {
            this.list.addBig(this.hintLanguageOption(phrases.hintLanguages(), c.hintLanguage));
         }

         this.list
            .addSmall(
               new OptionInstance[]{
                  cycle("zhnarrator.options.voice", phrases.voices(), c.voice, v -> c.voice = v),
                  cycle("zhnarrator.options.speed", phrases.speeds(), c.speed, v -> c.speed = v)
               }
            );
         this.list
            .addSmall(
               new OptionInstance[]{
                  percent("zhnarrator.options.volume", c.volume, v -> c.volume = v),
                  slider("zhnarrator.options.repeat", 1.0, 10.0, 0.5, c.repeatSeconds, " s", v -> c.repeatSeconds = v)
               }
            );
         this.list
            .addSmall(
               new OptionInstance[]{
                  slider("zhnarrator.options.text_scale", 1.0, 3.0, 0.25, c.hudScale, "×", v -> c.hudScale = v),
                  cycle("zhnarrator.options.font", List.of("default", "minecraft:default"), c.hudFont, v -> c.hudFont = v)
               }
            );
         if (phrases.meta().hasReading()) {
            this.list
               .addSmall(
                  new OptionInstance[]{
                     OptionInstance.createBoolean("zhnarrator.options.pinyin", c.hudShowPinyin, v -> c.hudShowPinyin = v),
                     OptionInstance.createBoolean("zhnarrator.options.english", c.hudShowEnglish, v -> c.hudShowEnglish = v)
                  }
               );
         } else {
            this.list.addBig(OptionInstance.createBoolean("zhnarrator.options.english", c.hudShowEnglish, v -> c.hudShowEnglish = v));
         }

         this.list
            .addSmall(
               new OptionInstance[]{
                  cycle("zhnarrator.options.quiz_mode", List.of("off", "choice", "speech", "mixed"), c.quizMode, v -> c.quizMode = v),
                  cycle("zhnarrator.options.quiz_penalty", List.of("none", "debuff", "hearts", "hardcore"), c.quizPenalty, v -> c.quizPenalty = v)
               }
            );
         this.list
            .addSmall(
               new OptionInstance[]{
                  slider("zhnarrator.options.quiz_interval", 15.0, 300.0, 15.0, c.quizIntervalSeconds, " s", v -> c.quizIntervalSeconds = v),
                  slider("zhnarrator.options.quiz_answer", 5.0, 30.0, 1.0, c.quizAnswerSeconds, " s", v -> c.quizAnswerSeconds = v)
               }
            );
         this.list
            .addSmall(
               new OptionInstance[]{
                  slider("zhnarrator.options.speech_strictness", 0.5, 1.0, 0.05, c.speechPassScore, "", v -> c.speechPassScore = v),
                  cycle("zhnarrator.options.speech_hint", List.of("new", "always", "never"), c.speechPinyinHint, v -> c.speechPinyinHint = v)
               }
            );
         this.list.addBig(this.voiceButton());
         this.list
            .addSmall(
               new OptionInstance[]{
                  cycle(
                     "zhnarrator.options.portrait",
                     List.of("bottom_right", "bottom_left", "top_right", "top_left", "off"),
                     c.hudPortrait,
                     v -> c.hudPortrait = v
                  ),
                  slider("zhnarrator.options.portrait_scale", 0.5, 4.0, 0.5, c.hudPortraitScale, "×", v -> c.hudPortraitScale = v)
               }
            );
         this.list
            .addSmall(
               new OptionInstance[]{
                  cycle(
                     "zhnarrator.options.counter", List.of("top_left", "top_right", "bottom_left", "bottom_right", "off"), c.hudCounter, v -> c.hudCounter = v
                  ),
                  slider("zhnarrator.options.counter_scale", 0.5, 3.0, 0.25, c.hudCounterScale, "×", v -> c.hudCounterScale = v)
               }
            );
         this.list
            .addBig(
               Button.builder(Component.translatable("zhnarrator.options.advanced"), b -> this.minecraft.gui.setScreen(new NarratorAdvancedScreen(this)))
                  .width(310)
                  .build()
            );
      }
   }

   private OptionInstance<String> languageOption(List<String> languages, String current) {
      LanguagePacks packs = ZhNarratorClient.packs;
      return new OptionInstance(
         "zhnarrator.options.language",
         OptionInstance.noTooltip(),
         (caption, v) -> Component.literal(packs.meta(v).map(LanguagePacks.Meta::label).orElse(v)),
         new Enum(languages, Codec.STRING),
         current,
         v -> {
            if (!v.equals(ZhNarratorClient.language())) {
               ZhNarratorClient.selectLanguage(v);
               this.minecraft.gui.setScreen(new NarratorSettingsScreen(this.parent));
            }
         }
      );
   }

   private OptionInstance<String> hintLanguageOption(List<String> available, String current) {
      List<String> values = new ArrayList<>();
      values.add("auto");
      values.addAll(available);
      Map<String, String> names = Map.of("zh", "中文", "es", "Español", "de", "Deutsch", "pt", "Português", "ja", "日本語", "en", "English");
      return new OptionInstance(
         "zhnarrator.options.hint_language",
         OptionInstance.noTooltip(),
         (caption, v) -> v.equals("auto") ? Component.translatable("zhnarrator.options.hint_language.auto") : Component.literal(names.getOrDefault(v, v)),
         new Enum(values, Codec.STRING),
         values.contains(current) ? current : "auto",
         v -> {
            if (!v.equals(ZhNarratorClient.config.hintLanguage)) {
               ZhNarratorClient.config.hintLanguage = v;
               ZhNarratorClient.saveConfig();
               this.minecraft.reloadResourcePacks();
            }
         }
      );
   }

   private Button voiceButton() {
      NarratorConfig c = ZhNarratorClient.config;
      VoiceHelper helper = ZhNarratorClient.voiceHelper;
      boolean active = true;
      String key;
      if (helper != null && VoiceHelper.supported()) {
         if (helper.state() == VoiceHelper.State.RUNNING) {
            key = "zhnarrator.options.voice_running";
            active = false;
         } else {
            if (helper.modelLoading()) {
               Button loading = Button.builder(
                     Component.translatable("zhnarrator.voice.loading_model", new Object[]{ZhNarratorClient.phrases.meta().name()}), b -> {}
                  )
                  .width(310)
                  .build();
               loading.active = false;
               return loading;
            }

            if (helper.busy()) {
               key = "zhnarrator.options.voice_starting";
               active = false;
            } else {
               key = c.voiceHelperAuto && helper.installed() ? "zhnarrator.options.voice_start" : "zhnarrator.options.voice_setup";
            }
         }
      } else {
         key = "zhnarrator.options.voice_unsupported";
         active = false;
      }

      Button button = Button.builder(Component.translatable(key), b -> {
         if (c.voiceHelperAuto && helper.installed()) {
            helper.start(this.minecraft);
            this.minecraft.gui.setScreen(new NarratorSettingsScreen(this.parent));
         } else {
            confirmVoiceSetup(this.minecraft, this.parent);
         }
      }).width(310).build();
      button.active = active;
      return button;
   }

   static void offerVoiceSetup(Minecraft mc, Screen titleScreen) {
      NarratorConfig c = ZhNarratorClient.config;
      c.voiceOffered = true;
      ZhNarratorClient.saveConfig();
      mc.gui.setScreen(new ConfirmScreen(yes -> {
         if (yes) {
            c.voiceHelperAuto = true;
            if (!c.quizMode.equals("speech")) {
               c.quizMode = "mixed";
            }

            ZhNarratorClient.saveConfig();
            ZhNarratorClient.voiceHelper.start(mc);
         }

         mc.gui.setScreen(titleScreen);
      }, Component.translatable("zhnarrator.voice.offer.title"), Component.translatable("zhnarrator.voice.confirm.message")));
   }

   static void confirmVoiceSetup(Minecraft mc, @Nullable Screen parent) {
      NarratorConfig c = ZhNarratorClient.config;
      mc.gui.setScreen(new ConfirmScreen(yes -> {
         if (yes) {
            c.voiceHelperAuto = true;
            if (!c.quizMode.equals("speech")) {
               c.quizMode = "mixed";
            }

            ZhNarratorClient.saveConfig();
            ZhNarratorClient.voiceHelper.start(mc);
         }

         mc.gui.setScreen(new NarratorSettingsScreen(parent));
      }, Component.translatable("zhnarrator.voice.confirm.title"), Component.translatable("zhnarrator.voice.confirm.message")));
   }

   public void removed() {
      super.removed();
      ZhNarratorClient.saveConfig();
   }

   static OptionInstance<String> cycle(String key, List<String> values, String current, Consumer<String> set) {
      String initial = values.contains(current) ? current : values.getFirst();
      return new OptionInstance(key, OptionInstance.noTooltip(), (caption, v) -> Component.literal(v), new Enum(values, Codec.STRING), initial, set::accept);
   }

   static OptionInstance<Double> percent(String key, float current, Consumer<Float> set) {
      return new OptionInstance(
         key,
         OptionInstance.noTooltip(),
         (caption, v) -> Component.literal(caption.getString() + ": " + Math.round(v * 100.0) + "%"),
         UnitDouble.INSTANCE,
         (double)current,
         v -> set.accept(v.floatValue())
      );
   }

   static OptionInstance<Double> slider(String key, double min, double max, double step, float current, String unit, Consumer<Float> set) {
      int steps = (int)Math.round((max - min) / step);
      double initial = Math.clamp(Math.round((current - min) / step) * step + min, min, max);
      return new OptionInstance(
         key,
         OptionInstance.noTooltip(),
         (caption, v) -> Component.literal(caption.getString() + ": " + format(v) + unit),
         new IntRange(0, steps).xmap(i -> min + i * step, d -> (int)Math.round((d - min) / step), true),
         initial,
         v -> set.accept(v.floatValue())
      );
   }

   private static String format(double v) {
      return v == Math.rint(v) ? String.valueOf((long)v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
   }
}
