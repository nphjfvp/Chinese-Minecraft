package dev.ame.zhnarrator;

import com.mojang.blaze3d.platform.InputConstants.Type;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription.Resource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZhNarratorClient {
   public static final String MOD_ID = "zhnarrator";
   public static final Logger LOGGER = LoggerFactory.getLogger("zhnarrator");
   public static NarratorConfig config = new NarratorConfig();
   @Nullable
   public static PhraseBook phrases;
   @Nullable
   public static Speaker speaker;
   public static Progress progress = new Progress();
   @Nullable
   public static QuizManager quiz;
   public static final LanguagePacks packs = new LanguagePacks();
   public static final String DEBUG_MODE = System.getProperty("zhnarrator.debugPrompt", "");
   public static final boolean DEBUG_PROMPT = !DEBUG_MODE.isEmpty();
   private int debugTicks;
   public static KeyMapping keyPinyin;
   public static KeyMapping keyTalk;
   public static KeyMapping keyOverview;
   private static KeyMapping keyFullbright;
   private static Path configFile;
   private static Path configDir;
   private static Path gameDir;
   @Nullable
   private static ActionDetector detector;
   private static final List<KeyMapping> KEYS = new ArrayList<>();
   private static KeyMapping keyToggle;
   private static KeyMapping keyWhatIsThis;
   private static KeyMapping keySpeed;
   private static KeyMapping keyReload;
   @Nullable
   public static VoiceHelper voiceHelper;
   private static int debugDoneAt;
   private static boolean noPackWarned;
   private static int ticksInWorld;
   private static int titleTicks;

   public boolean init(Path configDirectory, Path gameDirectory) {
      gameDir = gameDirectory;
      configDir = configDirectory;
      configFile = configDir.resolve("zhnarrator.json");
      config = NarratorConfig.load(configFile);
      Category category = Category.register(Identifier.fromNamespaceAndPath("zhnarrator", "main"));
      keyToggle = key("toggle", 17, category);
      keyPinyin = key("pinyin", 229, category);
      keyWhatIsThis = key("what_is_this", 25, category);
      keySpeed = key("speed", 14, category);
      keyReload = key("reload", 12, category);
      keyTalk = key("talk", 5, category);
      // Key codes are scancodes: 13 is J, 11 is H (G is taken by quick actions).
      keyOverview = key("overview", 13, category);
      keyFullbright = key("fullbright", 11, category);
      return true;
   }

   static void onLanguagePacksScanned(LanguagePacks found) {
      if (found.isEmpty()) {
         LOGGER.warn("No language pack installed: Learn or Die has nothing to teach until one is added to the mods folder");
         unloadLanguage();
      } else {
         String wanted = config.language;
         if (wanted == null || wanted.isEmpty() || found.meta(wanted).isEmpty()) {
            wanted = found.meta("zh").isPresent() ? "zh" : found.languages().getFirst();
         }

         if (phrases == null || !phrases.meta().language().equals(wanted) || "".equals(config.language)) {
            selectLanguage(wanted);
         }
      }
   }

   public static void selectLanguage(String language) {
      PhraseBook book;
      try {
         book = packs.load(language);
      } catch (IOException var4) {
         LOGGER.error("Language pack '{}' could not be loaded: {}", language, var4.getMessage());
         return;
      }

      if (speaker != null) {
         speaker.stop();
      }

      VoiceHelper oldHelper = voiceHelper;
      config.language = language;
      config.save(configFile);
      phrases = book;
      LOGGER.info(
         "Learn or Die: {} ({}), {} phrases, voices {}, speeds {}", new Object[]{book.meta().name(), language, book.size(), book.voices(), book.speeds()}
      );
      String progressName = language.equals("zh") ? "zhnarrator-progress.json" : "zhnarrator-progress-" + language + ".json";
      progress = Progress.load(configDir.resolve(progressName));
      speaker = new Speaker(book);
      quiz = new QuizManager(book, speaker, progress);
      voiceHelper = new VoiceHelper(quiz.speech(), oldHelper);
      detector = new ActionDetector(book, speaker);
      WordIcons.clear();
   }

   private static void unloadLanguage() {
      if (speaker != null) {
         speaker.stop();
      }

      phrases = null;
      speaker = null;
      quiz = null;
      detector = null;
   }

   public static String language() {
      return phrases == null ? "" : phrases.meta().language();
   }

   public static Path gameDir() {
      return gameDir;
   }

   @Nullable
   public static ActionDetector detector() {
      return detector;
   }

   public static List<KeyMapping> keys() {
      return KEYS;
   }

   public static void stopping() {
      if (voiceHelper != null) {
         voiceHelper.stopIfOurs();
      }
   }

   public static void appendTooltip(ItemStack stack, List<Component> lines) {
      if (phrases != null && config.tooltipChinese && !stack.isEmpty() && !lines.isEmpty()) {
         phrases.forItem("hold", ActionDetector.itemPath(stack)).ifPresent(p -> {
            Style style = Style.EMPTY.withFont(new Resource(NarratorHud.fontId()));
            lines.add(1, Component.literal(p.labelWithReading()).withStyle(style.withColor(16769162)));
         });
      }
   }

   public static KeyMapping keySettings() {
      return keyReload;
   }

   private static KeyMapping key(String name, int glfwKey, Category category) {
      KeyMapping key = new KeyMapping("key.zhnarrator." + name, Type.KEYBOARD, glfwKey, category);
      KEYS.add(key);
      return key;
   }

   public void tick(Minecraft mc) {
      while (keyReload.consumeClick()) {
         mc.gui.setScreen(new NarratorSettingsScreen(null));
      }

      if (phrases != null && speaker != null && quiz != null && detector != null && voiceHelper != null) {
         if (mc.player != null && !config.languageHinted && packs.languages().size() > 1) {
            config.languageHinted = true;
            config.save(configFile);
            mc.player
               .sendSystemMessage(
                  Component.literal("[Teacher] ")
                     .append(Component.translatable("zhnarrator.choose_language", new Object[]{phrases.meta().label(), keyName(keyReload)}))
               );
         }

         ticksInWorld = mc.player == null ? 0 : ticksInWorld + 1;
         if (ticksInWorld == Integer.getInteger("zhnarrator.voiceHintTicks", 1200)
            && !config.voiceHinted
            && config.enabled
            && VoiceHelper.supported()
            && !voiceHelper.installed()) {
            config.voiceHinted = true;
            config.save(configFile);
            mc.player
               .sendSystemMessage(
                  Component.literal("[Teacher] ")
                     .append(Component.translatable("zhnarrator.voice.hint", new Object[]{keyName(keyReload)}))
                     .withStyle(ChatFormatting.YELLOW)
               );
         }

         while (keyToggle.consumeClick()) {
            config.enabled = !config.enabled;
            config.save(configFile);
            speaker.stop();
            actionBar(mc, config.enabled ? "Teacher on" : "Teacher off");
         }

         while (keySpeed.consumeClick()) {
            speaker.cycleSpeed();
            config.save(configFile);
            actionBar(mc, "Speech speed: " + config.speed);
         }

         while (keyWhatIsThis.consumeClick()) {
            detector.sayLookedAt(mc);
         }

         while (keyOverview.consumeClick()) {
            if (mc.player != null) {
               mc.gui.setScreen(new LearningScreen());
            }
         }

         while (keyFullbright.consumeClick()) {
            config.fullbright = !config.fullbright;
            config.save(configFile);
            actionBar(mc, config.fullbright ? "Fullbright on" : "Fullbright off");
         }

         if (mc.player != null && mc.level != null && !mc.isPaused()) {
            detector.tick(mc);
            quiz.tick(mc);
         }

         if (DEBUG_PROMPT && mc.player != null && mc.level != null) {
            this.debugTick(mc);
         }

         if (mc.gui.screen() instanceof TitleScreen title
            && !config.voiceOffered
            && !config.voiceHelperAuto
            && VoiceHelper.supported()
            && !voiceHelper.installed()) {
            NarratorSettingsScreen.offerVoiceSetup(mc, title);
         }

         if (DEBUG_MODE.equals("title") && mc.gui.screen() instanceof ConfirmScreen) {
            if (++titleTicks == 200) {
               Screenshot.grab(mc, false);
            } else if (titleTicks == 240) {
               mc.stop();
            }
         }

         voiceHelper.tick(mc);
         speaker.tick(mc);
         progress.tick();
      } else {
         if (mc.player != null && !noPackWarned && packs.isEmpty()) {
            noPackWarned = true;
            mc.player.sendSystemMessage(Component.literal("[Teacher] ").append(Component.translatable("zhnarrator.no_pack")));
         }
      }
   }

   public static Component keyName(KeyMapping key) {
      String saved = key.saveString();
      String prefix = "key.keyboard.";
      return (Component)(saved.startsWith(prefix) && saved.length() == prefix.length() + 1
         ? Component.literal(saved.substring(prefix.length()).toUpperCase(Locale.ROOT))
         : key.getTranslatedKeyMessage());
   }

   private static void debugCommand(Minecraft mc, String command) {
      IntegratedServer server = mc.getSingleplayerServer();
      if (server != null && mc.player != null) {
         UUID id = mc.player.getUUID();
         server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
               server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS), command);
            }
         });
      }
   }

   private void debugTick(Minecraft mc) {
      this.debugTicks++;
      String var2 = DEBUG_MODE;
      switch (var2) {
         case "voice":
            if (this.debugTicks == 120) {
               voiceHelper.start(mc);
            }

            VoiceHelper.State s = voiceHelper.state();
            if (s == VoiceHelper.State.STARTING && this.debugTicks % 100 == 0) {
               LOGGER.info(
                  "debug: voice setup {}% ({} of {} bytes)", new Object[]{voiceHelper.setupPercent(), voiceHelper.setupDone(), voiceHelper.setupTotal()}
               );
            }

            if (this.debugTicks == 920) {
               Screenshot.grab(mc, false);
            }

            if (debugDoneAt == 0 && this.debugTicks > 120 && (s == VoiceHelper.State.RUNNING || s == VoiceHelper.State.FAILED || this.debugTicks > 36000)) {
               debugDoneAt = this.debugTicks;
               LOGGER.info("debug: voice helper {}", s);
            }

            if (debugDoneAt > 0 && this.debugTicks == debugDoneAt + 60) {
               Screenshot.grab(mc, false);
            } else if (debugDoneAt > 0 && this.debugTicks == debugDoneAt + 120) {
               mc.stop();
            }
            break;
         case "voiceswitch":
            if (this.debugTicks == 120) {
               voiceHelper.start(mc);
            }

            if (debugDoneAt == 0 && this.debugTicks > 120 && voiceHelper.state() == VoiceHelper.State.RUNNING) {
               debugDoneAt = this.debugTicks;
               String to = System.getProperty("zhnarrator.switchTo", "es");
               LOGGER.info("debug: helper running, switching to {}", to);
               selectLanguage(to);
            }

            if (debugDoneAt > 0 && this.debugTicks % 100 == 0) {
               LOGGER.info(
                  "debug: model loading {} setup {}% ({} bytes) state {}",
                  new Object[]{voiceHelper.modelLoading(), voiceHelper.setupPercent(), voiceHelper.setupDone(), voiceHelper.state()}
               );
            }

            if (debugDoneAt > 0 && (this.debugTicks == debugDoneAt + 40 || this.debugTicks == debugDoneAt + 800)) {
               Screenshot.grab(mc, false);
            }

            if (debugDoneAt > 0
               && (this.debugTicks > debugDoneAt + 200 && voiceHelper.state() == VoiceHelper.State.RUNNING || this.debugTicks > debugDoneAt + 9600)) {
               LOGGER.info("debug: voice switch done, state {}", voiceHelper.state());
               mc.stop();
            }
            break;
         case "voiceui":
            if (this.debugTicks == 120) {
               mc.gui.setScreen(new NarratorSettingsScreen(null));
            } else if (this.debugTicks == 160 || this.debugTicks == 240) {
               Screenshot.grab(mc, false);
            } else if (this.debugTicks == 200) {
               NarratorSettingsScreen.confirmVoiceSetup(mc, null);
            } else if (this.debugTicks == 280) {
               mc.stop();
            }
            break;
         case "events":
            BlockPos at = mc.player.blockPosition();
            if (this.debugTicks == 100) {
               debugCommand(mc, "setblock ~2 ~ ~ minecraft:dirt");
               debugCommand(mc, "setblock ~-2 ~ ~ minecraft:oak_trapdoor[open=true]");
               debugCommand(mc, "summon minecraft:pig ~ ~ ~3 {NoAI:1b}");
            } else if (this.debugTicks == 130) {
               LOGGER.info("debug: destroyBlock dirt -> {}", mc.gameMode.destroyBlock(at.east(2)));
            } else if (this.debugTicks == 150) {
               BlockPos trapdoor = at.west(2);
               BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(trapdoor), Direction.UP, trapdoor, false);
               LOGGER.info("debug: useItemOn trapdoor -> {}", mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit));
            } else if (this.debugTicks == 170) {
               mc.level.getEntitiesOfClass(Pig.class, mc.player.getBoundingBox().inflate(6.0)).stream().findFirst().ifPresentOrElse(pig -> {
                  mc.gameMode.attack(mc.player, pig);
                  LOGGER.info("debug: attacked the pig");
               }, () -> LOGGER.info("debug: no pig to attack"));
            } else if (this.debugTicks == 190) {
               List<Component> lines = new ItemStack(Items.DIRT).getTooltipLines(TooltipContext.EMPTY, mc.player, TooltipFlag.NORMAL);
               LOGGER.info("debug: dirt tooltip {}", lines.stream().map(Component::getString).toList());
               long registered = Arrays.stream(mc.options.keyMappings).filter(KEYS::contains).count();
               LOGGER.info("debug: {} of {} keys registered", registered, KEYS.size());
            } else if (this.debugTicks == 210) {
               debugCommand(mc, "give @s minecraft:poppy 4");
            } else if (this.debugTicks == 260) {
               mc.stop();
            }
            break;
         case "shoot":
            if (this.debugTicks == 120) {
               quiz.startShooting(mc);
            } else if (this.debugTicks == 128 || this.debugTicks == 138) {
               Screenshot.grab(mc, false);
            } else if (this.debugTicks == 200) {
               mc.stop();
            }
            break;
         case "portrait":
            Phrase q = phrases.fixed("break.dirt").orElse(null);
            if (q == null) {
               return;
            }

            if (this.debugTicks >= 100 && this.debugTicks <= 130 && this.debugTicks % 2 == 0) {
               LOGGER.info("debug: mouth {} speaking {}", speaker.mouth(System.currentTimeMillis()), speaker.isSpeaking());
            }

            if (this.debugTicks == 100) {
               quiz.answer(mc, q, true);
            } else if (this.debugTicks == 104 || this.debugTicks == 130) {
               Screenshot.grab(mc, false);
            } else if (this.debugTicks == 150) {
               for (int i = 0; i < Math.max(1, config.angryAfterWrong); i++) {
                  quiz.answer(mc, q, false);
               }
            } else if (this.debugTicks == 156 || this.debugTicks == 157) {
               Screenshot.grab(mc, false);
            } else if (this.debugTicks == 240) {
               mc.stop();
            }
            break;
         default:
            if (this.debugTicks == 120) {
               if (DEBUG_MODE.equals("choice")) {
                  quiz.debugAskChoice(mc, "break.dirt");
               } else {
                  quiz.debugAskSpeech("break.dirt");
               }
            } else if (this.debugTicks == 200) {
               Screenshot.grab(mc, false);
               LOGGER.info("debug: screenshot requested");
            } else if (this.debugTicks == 260) {
               mc.stop();
            }
      }
   }

   public static void saveConfig() {
      config.save(configFile);
   }

   static void actionBar(Minecraft mc, String message) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal("[Teacher] " + message));
      }
   }
}
