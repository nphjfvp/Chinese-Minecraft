package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription.Resource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

public final class NarratorHud {
   public static final Identifier LAYER = Identifier.fromNamespaceAndPath("zhnarrator", "narrator");
   private static final int HAPPY = 8;
   private static final int FROWN_TALK = 10;
   private static final long HAPPY_MS = 1200L;
   private static long nextBlinkAt;
   private static long hopAt;
   static final int PANEL_BG = 1316378;
   static final int GOLD = 15119394;
   @Nullable
   private static Phrase bannerPhrase;
   private static boolean bannerIsWord;
   private static long bannerUntil;
   @Nullable
   private static Phrase heldLabel;
   private static long heldLabelAt;

   private NarratorHud() {
   }

   public static void render(GuiGraphicsExtractor g, Speaker speaker) {
      NarratorConfig c = ZhNarratorClient.config;
      Minecraft mc = Minecraft.getInstance();
      if (speaker != null && ZhNarratorClient.phrases != null) {
         renderPortrait(g, speaker);
         renderCounter(g, mc);
         renderHeldLabel(g, mc);
         renderQuizPrompt(g, mc);
         renderBanner(g, mc);
         Phrase p = speaker.shown();
         if (c.hudEnabled && p != null) {
            int alpha = Math.max(16, Math.round(255.0F * speaker.visibility()));
            Font font = mc.font;
            Style style = Style.EMPTY.withFont(new Resource(fontId()));
            Component zh = Component.literal(p.text()).withStyle(style);
            Component pinyin = Component.literal(p.reading()).withStyle(style);
            Component en = Component.literal(p.en()).withStyle(style);
            boolean showPinyin = p.hasReading() && (c.hudShowPinyin || ZhNarratorClient.keyPinyin != null && ZhNarratorClient.keyPinyin.isDown());
            boolean showEnglish = c.hudShowEnglish && !p.action().equals("hold") && !p.en().equals(p.text());
            float scale = Math.max(0.5F, c.hudScale);
            float small = scale * 0.6F;
            float centerX = g.guiWidth() / 2.0F;
            int smallLines = (showPinyin ? 1 : 0) + (showEnglish ? 1 : 0);
            float blockHeight = (9 + 2) * scale + smallLines * (9 + 1) * small;
            String pose = c.hudAnchor;

            float y = switch (pose) {
               case "top" -> c.hudOffset;
               case "fraction" -> g.guiHeight() * c.hudY;
               default -> g.guiHeight() - c.hudOffset - blockHeight;
            };
            if (c.hudBackdrop) {
               int w = Math.round(font.width(zh) * scale);
               if (showPinyin) {
                  w = Math.max(w, Math.round(font.width(pinyin) * small));
               }

               if (showEnglish) {
                  w = Math.max(w, Math.round(font.width(en) * small));
               }

               int bx0 = Math.round(centerX - w / 2.0F) - 8;
               int by0 = Math.round(y) - 3;
               g.fill(bx0, by0, bx0 + w + 16, Math.round(y + blockHeight) + 1, alpha * 105 / 255 << 24 | 1316378);
            }

            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(centerX, y);
            pose.scale(scale, scale);
            int line = 0;
            drawColoured(g, font, p, style, false, alpha, c);
            line += 9 + 2;
            pose.popMatrix();
            pose.pushMatrix();
            pose.translate(centerX, y + line * scale);
            pose.scale(small, small);
            int smallLine = 0;
            if (showPinyin) {
               drawColoured(g, font, p, style, true, alpha, c);
               smallLine += 9 + 1;
            }

            if (showEnglish) {
               g.text(font, en, -font.width(en) / 2, smallLine, withAlpha(c.hudColorEn, alpha), c.hudShadow);
            }

            pose.popMatrix();
         }
      }
   }

   static void hop() {
      hopAt = System.currentTimeMillis();
   }

   private static float hopLift(long now, float height) {
      long t = now - hopAt;
      float slower = (float)Math.cbrt(height / 6.0F);
      float up = 300.0F * slower;
      float bounce = 220.0F * slower;
      if (t >= 0L && !((float)t >= up + bounce)) {
         return (float)t < up ? height * (float)Math.sin(Math.PI * t / up) : height / 2.0F * (float)Math.sin(Math.PI * ((float)t - up) / bounce);
      } else {
         return 0.0F;
      }
   }

   private static void renderPortrait(GuiGraphicsExtractor g, Speaker speaker) {
      NarratorConfig c = ZhNarratorClient.config;
      if (c.enabled && c.hudPortrait != null && !c.hudPortrait.equals("off")) {
         long now = System.currentTimeMillis();
         int frame = 0;
         QuizManager quiz = ZhNarratorClient.quiz;
         boolean blink = false;
         if (nextBlinkAt == 0L || now > nextBlinkAt + 150L) {
            nextBlinkAt = now + 2500L + now % 2500L;
         } else if (now >= nextBlinkAt) {
            blink = true;
         }

         LanguagePacks.Meta meta = ZhNarratorClient.phrases.meta();
         int headDx = 0;
         int shoot = quiz == null ? -1 : quiz.shootFrame(now);
         if (shoot >= 0 && shoot < meta.portraitFrames()) {
            frame = shoot;
         } else if (quiz != null && quiz.isAngry()) {
            frame = blink ? 6 : 5;
            if (speaker.isSpeaking()) {
               headDx = now / 40L % 2L == 0L ? 1 : 0;
            }
         } else if (meta.portraitFrames() > 8 && now - hopAt < 1200L) {
            int mouth = speaker.mouth(now);
            frame = mouth > 0 && meta.portraitFrames() > 8 + mouth ? 8 + mouth : 8;
         } else if (quiz != null && quiz.isFrowning()) {
            int mouth = speaker.mouth(now);
            frame = mouth > 0 && meta.portraitFrames() > 10 + mouth ? 10 + mouth : 4;
         } else if (speaker.isSpeaking()) {
            int mouth = speaker.mouth(now);
            frame = mouth > 0 ? mouth : (blink ? 3 : 0);
         } else if (blink) {
            frame = 3;
         }

         float scale = Math.max(0.5F, Math.round(c.hudPortraitScale * 2.0F) / 2.0F);
         int fw = meta.portraitWidth();
         int fh = meta.portraitHeight();
         int waist = meta.portraitWaist();
         float units = meta.portraitScreenHeight() / waist;
         boolean right = c.hudPortrait.endsWith("right");
         boolean bottom = c.hudPortrait.startsWith("bottom");
         float x = right ? g.guiWidth() - 6 - fw * units * scale : 6.0F;
         float y = bottom ? g.guiHeight() - waist * units * scale : 6.0F;
         float lift = Math.min(hopLift(now, meta.portraitHop()) * scale, y);
         int shown = Math.min(fh, waist + (int)Math.ceil(lift / (units * scale)));
         Matrix3x2fStack pose = g.pose();
         pose.pushMatrix();
         pose.translate(x, y - lift);
         pose.scale(scale * units, scale * units);
         Identifier portrait = ZhNarratorClient.phrases.portrait();
         int neck = meta.portraitNeck();
         int sheetW = fw * meta.portraitFrames();
         g.blit(RenderPipelines.GUI_TEXTURED, portrait, headDx, 0, frame * fw, 0.0F, fw, neck, sheetW, fh, -1);
         g.blit(RenderPipelines.GUI_TEXTURED, portrait, 0, neck, frame * fw, neck, fw, shown - neck, sheetW, fh, -1);
         pose.popMatrix();
      }
   }

   private static void renderCounter(GuiGraphicsExtractor g, Minecraft mc) {
      NarratorConfig c = ZhNarratorClient.config;
      boolean counter = c.hudCounter != null && !c.hudCounter.equals("off");
      NarratorHud.Line timer = c.hudQuizTimer ? quizTimerLine(mc) : null;
      if (counter || timer != null) {
         Progress progress = ZhNarratorClient.progress;
         Font font = mc.font;
         Style kai = Style.EMPTY.withFont(new Resource(fontId()));
         String pos = counter ? c.hudCounter : "top_left";
         boolean right = pos.endsWith("right");
         boolean bottom = pos.startsWith("bottom");
         float scale = Math.max(0.5F, c.hudCounterScale);
         float numScale = 1.6F;
         int pad = 6;
         int gap = 14;
         int panelH = 23 + 9 + 5;
         boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
         int learned = progress.learnedWords(ZhNarratorClient.phrases);
         int deaths = hardcore ? progress.deaths : progress.world().deaths;
         Component learnedNum = Component.literal(Integer.toString(learned)).withStyle(kai);
         Component deathsNum = Component.literal(Integer.toString(deaths)).withStyle(kai);
         Component learnedCap = Component.translatable("zhnarrator.counter.learned").withStyle(kai);
         Component deathsCap = Component.translatable("zhnarrator.counter.deaths").withStyle(kai);
         int cell1 = Math.max(20 + Math.round(font.width(learnedNum) * 1.6F), font.width(learnedCap));
         int cell2 = Math.max(20 + Math.round(font.width(deathsNum) * 1.6F), font.width(deathsCap));
         int panelW = 6 + cell1 + 14 + cell2 + 6;
         Component timerText = timer == null ? null : timer.text().copy().withStyle(kai);
         int timerW = timerText == null ? 0 : font.width(timerText);
         int blockW = counter ? Math.max(panelW, timerW) : timerW;
         int blockH = (counter ? panelH : 0) + (timerText != null ? 9 + (counter ? 3 : 0) : 0);
         float x = right ? g.guiWidth() - 6 - blockW * scale : 6.0F;
         float y = bottom ? g.guiHeight() - 6 - blockH * scale : 6.0F;
         Matrix3x2fStack pose = g.pose();
         pose.pushMatrix();
         pose.translate(x, y);
         pose.scale(scale, scale);
         int cy = 0;
         if (counter) {
            int px0 = right ? blockW - panelW : 0;
            panel(g, px0, 0, px0 + panelW, panelH, 255);
            tile(g, font, px0 + 6, 5, Items.BOOK, learnedNum, -8054, learnedCap, 1.6F);
            tile(g, font, px0 + 6 + cell1 + 14, 5, Items.SKELETON_SKULL, deathsNum, deaths > 0 ? -30070 : -4473925, deathsCap, 1.6F);
            cy = panelH + 3;
         }

         if (timerText != null) {
            g.text(font, timerText, right ? blockW - timerW : 0, cy, timer.colour(), true);
         }

         pose.popMatrix();
      }
   }

   private static void tile(GuiGraphicsExtractor g, Font font, int x, int y, Item item, Component number, int colour, Component caption, float numScale) {
      WordIcons.draw(g, new ItemStack(item), x, y, 1.0F);
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(x + 20, y + (16.0F - 9.0F * numScale) / 2.0F);
      pose.scale(numScale, numScale);
      g.text(font, number, 0, 0, colour, true);
      pose.popMatrix();
      g.text(font, caption, x, y + 16 + 2, -4473925, true);
   }

   private static void neonFrame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int rgb, int alpha) {
      int[] halo = new int[]{38, 18, 7};

      for (int i = halo.length; i >= 1; i--) {
         frame(g, x0 - i, y0 - i, x1 + i, y1 + i, alpha * halo[i - 1] / 100 << 24 | rgb);
      }

      frame(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, alpha * 22 / 100 << 24 | rgb);
      frame(g, x0, y0, x1, y1, alpha * 88 / 100 << 24 | rgb);
   }

   static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int alpha) {
      panel(g, x0, y0, x1, y1, alpha, 15119394);
   }

   static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int alpha, int lineRgb) {
      g.fill(x0, y0, x1, y1, alpha * 150 / 255 << 24 | 1316378);
      frame(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, alpha * 30 / 255 << 24 | lineRgb);
      frame(g, x0, y0, x1, y1, alpha * 95 / 255 << 24 | lineRgb);
   }

   static void timeBar(GuiGraphicsExtractor g, int x0, int y, int w, float fraction, int alpha) {
      float f = Math.max(0.0F, Math.min(1.0F, fraction));
      g.fill(x0, y, x0 + w, y + 2, alpha * 60 / 255 << 24 | 16777215);
      g.fill(x0, y, x0 + Math.round(w * f), y + 2, alpha << 24 | (f < 0.25F ? 16734810 : 15119394));
   }

   static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
      g.fill(x0, y0, x1, y0 + 1, argb);
      g.fill(x0, y1 - 1, x1, y1, argb);
      g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
      g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
   }

   @Nullable
   private static NarratorHud.Line quizTimerLine(Minecraft mc) {
      NarratorConfig c = ZhNarratorClient.config;
      QuizManager quiz = ZhNarratorClient.quiz;
      if (quiz != null && mc.player != null && c.enabled && !c.quizMode.equals("off") && !quiz.questionOpen()) {
         long now = System.currentTimeMillis();
         VoiceHelper setup = ZhNarratorClient.voiceHelper;
         if (setup != null && setup.setupDone() > 20000000L) {
            return new NarratorHud.Line(
               Component.translatable(
                  "zhnarrator.timer.helper_progress",
                  new Object[]{
                     setup.setupPercent(),
                     String.format(Locale.ROOT, "%.1f", setup.setupDone() / 1.0E9),
                     String.format(Locale.ROOT, "%.1f", setup.setupTotal() / 1.0E9)
                  }
               ),
               -14467
            );
         } else if (quiz.helperMissing()) {
            VoiceHelper helper = ZhNarratorClient.voiceHelper;
            if (helper != null && helper.modelLoading()) {
               return new NarratorHud.Line(
                  Component.translatable(
                     "zhnarrator.voice.loading_model_progress", new Object[]{ZhNarratorClient.phrases.meta().name(), helper.loadingPercent()}
                  ),
                  -14467
               );
            } else {
               return helper != null && helper.busy()
                  ? new NarratorHud.Line(Component.translatable("zhnarrator.timer.helper_starting"), -14467)
                  : new NarratorHud.Line(Component.translatable("zhnarrator.timer.helper"), -30070);
            }
         } else if (ZhNarratorClient.progress.world().heard.isEmpty()) {
            return new NarratorHud.Line(Component.translatable("zhnarrator.timer.first"), -14467);
         } else if (quiz.nothingToAsk()) {
            return new NarratorHud.Line(Component.translatable("zhnarrator.timer.nothing"), -14467);
         } else {
            long left = quiz.nextQuestionInMs(now);
            if (left > 0L) {
               long seconds = (left + 999L) / 1000L;
               return new NarratorHud.Line(Component.translatable("zhnarrator.timer.next", new Object[]{seconds}), seconds <= 3L ? -8054 : -4473925);
            } else {
               String reason = quiz.waitReason(mc.player, now);
               return new NarratorHud.Line(Component.translatable(reason == null ? "zhnarrator.timer.now" : "zhnarrator.timer.wait." + reason), -14467);
            }
         }
      } else {
         return null;
      }
   }

   private static void renderQuizPrompt(GuiGraphicsExtractor g, Minecraft mc) {
      QuizManager quiz = ZhNarratorClient.quiz;
      Phrase q = quiz == null ? null : quiz.pending();
      if (q != null && quiz.phase() != QuizManager.Phase.NONE) {
         NarratorConfig c;
         Font font;
         float cx;
         Component title;
         Component prompt;
         boolean var10000;
         label132: {
            c = ZhNarratorClient.config;
            font = mc.font;
            cx = g.guiWidth() / 2.0F;
            title = Component.translatable("zhnarrator.quiz.say", new Object[]{ZhNarratorClient.phrases.meta().name()});
            prompt = Component.literal(q.en());
            if (!quiz.hintNow()) {
               String py = c.speechPinyinHint;
               switch (py) {
                  case "always":
                     break;
                  default:
                     if (ZhNarratorClient.progress.correctEver(q.id()) == 0) {
                        break;
                     }
                  case "never":
                     var10000 = false;
                     break label132;
               }
            }

            var10000 = true;
         }

         boolean hint = var10000;
         Component py = hint ? colouredComponent(q, q.hasReading(), Style.EMPTY, c.hudColorPinyin) : null;
         Component zh = py != null && q.hasReading() ? colouredComponent(q, false, Style.EMPTY.withFont(new Resource(fontId())), c.hudColorZh) : null;
         if (q.en().equals(q.text())) {
            py = null;
            zh = null;
         }

         QuizManager.Phase phase = quiz.phase();

         Component status = switch (phase) {
            case RECORDING -> Component.translatable("zhnarrator.quiz.recording");
            case CHECKING -> Component.translatable("zhnarrator.quiz.checking");
            default -> Component.translatable(
               "zhnarrator.quiz.hold", new Object[]{ZhNarratorClient.keyName(ZhNarratorClient.keyTalk), String.format("%.0f", quiz.secondsLeft())}
            );
         };

         int statusColour = switch (phase) {
            case RECORDING -> -34182;
            case CHECKING -> -5588737;
            default -> quiz.secondsLeft() < 3.0F ? -34182 : -4473925;
         };

         int lineRgb = switch (phase) {
            case RECORDING -> 16734810;
            case CHECKING -> 10205439;
            default -> 15119394;
         };
         Optional<ItemStack> icon = c.quizShowIcons ? WordIcons.iconFor(q) : Optional.empty();
         float pyScale = 1.3F;
         int textW = Math.max(Math.max(font.width(title), font.width(prompt)), Math.max(py == null ? 0 : Math.round(font.width(py) * 1.3F), font.width(status)));
         textW = Math.max(textW, zh == null ? 0 : Math.round(font.width(zh) * 1.3F));
         int pad = 12;
         int iconW = icon.isPresent() ? 48 : 0;
         int panelW = pad + iconW + textW + pad;
         int textH = 20 + (zh != null ? 14 : 0) + (py != null ? 14 : 0) + 3 + 9;
         int panelH = 7 + textH + 7 + 2 + 5;
         int x0 = Math.round(cx - panelW / 2.0F);
         int y0 = 20;
         panel(g, x0, y0, x0 + panelW, y0 + panelH, 255, lineRgb);
         float tx = cx + iconW / 2.0F;
         if (icon.isPresent()) {
            int ix = x0 + pad - 3;
            int iy = y0 + 7 + textH / 2 - 18;
            g.fill(ix, iy, ix + 36, iy + 36, 1342177280);
            frame(g, ix, iy, ix + 36, iy + 36, 1088861218);
            WordIcons.draw(g, icon.get(), ix + 2, iy + 2, 2.0F);
         }

         int y = y0 + 7;
         g.text(font, title, Math.round(tx - font.width(title) / 2.0F), y, -4473925, true);
         y += 11;
         g.text(font, prompt, Math.round(tx - font.width(prompt) / 2.0F), y, -8054, true);
         y += 11;
         if (zh != null) {
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(tx, y);
            pose.scale(1.3F, 1.3F);
            g.text(font, zh, -font.width(zh) / 2, 0, -1, true);
            pose.popMatrix();
            y += 14;
         }

         if (py != null) {
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(tx, y);
            pose.scale(1.3F, 1.3F);
            g.text(font, py, -font.width(py) / 2, 0, -1, true);
            pose.popMatrix();
            y += 14;
         }

         y++;
         g.text(font, status, Math.round(tx - font.width(status) / 2.0F), y, statusColour, true);
         timeBar(g, x0 + 8, y0 + panelH - 7, panelW - 16, quiz.fractionLeft(), 255);
      }
   }

   private static String pieceText(Phrase p, int i, boolean reading) {
      Phrase.Part part = p.parts().get(i);
      String sep = reading ? " " : (ZhNarratorClient.phrases == null ? "" : ZhNarratorClient.phrases.meta().wordSeparator());
      return (reading ? part.reading() : part.text()) + (i < p.parts().size() - 1 ? sep : "");
   }

   private static String tail(Phrase p) {
      StringBuilder joined = new StringBuilder();

      for (int i = 0; i < p.parts().size(); i++) {
         joined.append(pieceText(p, i, false));
      }

      return p.text().startsWith(joined.toString()) ? p.text().substring(joined.length()) : "";
   }

   private static void drawColoured(GuiGraphicsExtractor g, Font font, Phrase p, Style style, boolean pinyin, int alpha, NarratorConfig c) {
      if (!p.parts().isEmpty() && c.hudColourGrammar) {
         List<Component> pieces = new ArrayList<>();
         int total = 0;

         for (int i = 0; i < p.parts().size(); i++) {
            Component piece = Component.literal(pieceText(p, i, pinyin)).withStyle(style);
            pieces.add(piece);
            total += font.width(piece);
         }

         if (!pinyin && !tail(p).isEmpty()) {
            pieces.add(Component.literal(tail(p)).withStyle(style));
            total += font.width((FormattedText)pieces.getLast());
         }

         int x = -total / 2;

         for (int i = 0; i < pieces.size(); i++) {
            String role = i < p.parts().size() ? p.parts().get(i).role() : "part";

            int colour = switch (role) {
               case "pron" -> c.hudColorPronoun;
               case "verb" -> c.hudColorVerb;
               case "obj" -> c.hudColorObject;
               case "adj" -> c.hudColorAdjective;
               case "num" -> c.hudColorNumber;
               case "part" -> c.hudColorParticle;
               default -> pinyin ? c.hudColorPinyin : c.hudColorZh;
            };
            if (pinyin) {
               colour = blend(colour, c.hudColorPinyin);
            }

            g.text(font, pieces.get(i), x, 0, withAlpha(colour, alpha), c.hudShadow);
            x += font.width((FormattedText)pieces.get(i));
         }
      } else {
         Component whole = Component.literal(pinyin ? p.reading() : p.text()).withStyle(style);
         g.text(font, whole, -font.width(whole) / 2, 0, withAlpha(pinyin ? c.hudColorPinyin : c.hudColorZh, alpha), c.hudShadow);
      }
   }

   public static MutableComponent colouredComponent(Phrase p, boolean pinyin, Style base, int fallbackColour) {
      return colouredComponent(p, pinyin, base, fallbackColour, 0.0F);
   }

   public static MutableComponent colouredComponent(Phrase p, boolean pinyin, Style base, int fallbackColour, float lift) {
      NarratorConfig c = ZhNarratorClient.config;
      if (!p.parts().isEmpty() && c.hudColourGrammar) {
         MutableComponent out = Component.empty();

         for (int i = 0; i < p.parts().size(); i++) {
            Phrase.Part part = p.parts().get(i);
            String var10 = part.role();

            int colour = switch (var10) {
               case "pron" -> c.hudColorPronoun;
               case "verb" -> c.hudColorVerb;
               case "obj" -> c.hudColorObject;
               case "adj" -> c.hudColorAdjective;
               case "num" -> c.hudColorNumber;
               case "part" -> c.hudColorParticle;
               default -> fallbackColour;
            };
            if (pinyin) {
               colour = blend(colour, c.hudColorPinyin);
            }

            out.append(Component.literal(pieceText(p, i, pinyin)).withStyle(base.withColor(lift(colour, lift) & 16777215)));
         }

         if (!pinyin && !tail(p).isEmpty()) {
            out.append(Component.literal(tail(p)).withStyle(base.withColor(lift(c.hudColorParticle, lift) & 16777215)));
         }

         return out;
      } else {
         return Component.literal(pinyin ? p.reading() : p.text()).withStyle(base.withColor(lift(fallbackColour, lift) & 16777215));
      }
   }

   private static int lift(int rgb, float f) {
      if (f <= 0.0F) {
         return rgb;
      } else {
         int r = rgb >> 16 & 0xFF;
         int gr = rgb >> 8 & 0xFF;
         int bl = rgb & 0xFF;
         r += Math.round((255 - r) * f);
         gr += Math.round((255 - gr) * f);
         bl += Math.round((255 - bl) * f);
         return r << 16 | gr << 8 | bl;
      }
   }

   private static int blend(int a, int b) {
      int r = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF)) / 2;
      int gr = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF)) / 2;
      int bl = ((a & 0xFF) + (b & 0xFF)) / 2;
      return r << 16 | gr << 8 | bl;
   }

   public static void showBanner(Phrase learned, boolean isWord, long ms) {
      bannerPhrase = learned;
      bannerIsWord = isWord;
      bannerUntil = System.currentTimeMillis() + ms;
   }

   private static void renderBanner(GuiGraphicsExtractor g, Minecraft mc) {
      long now = System.currentTimeMillis();
      if (now < bannerUntil) {
         Phrase p = bannerPhrase;
         if (p != null) {
            NarratorConfig c = ZhNarratorClient.config;
            Font font = mc.font;
            Style kai = Style.EMPTY.withFont(new Resource(fontId()));
            Component title = Component.translatable(bannerIsWord ? "zhnarrator.learned.word" : "zhnarrator.learned.phrase");
            Component line = Component.literal(p.bare() + "  —  " + p.en().replaceAll("[.!]$", "")).withStyle(kai);
            Optional<ItemStack> icon = bannerIsWord ? WordIcons.iconFor(p) : Optional.empty();
            int alpha = (int)Math.max(40.0, Math.min(255.0, (bannerUntil - now) / 3.0));
            float cx = g.guiWidth() / 2.0F;
            int textW = Math.max(font.width(title), (int)(font.width(line) * 1.5F));
            int w = textW + 24 + (icon.isPresent() ? 40 : 0);
            int y = 92;
            int x0 = (int)(cx - w / 2.0F);
            g.fill(x0, y, x0 + w, y + 38, alpha * 160 / 255 << 24 | 1708544);
            neonFrame(g, x0, y, x0 + w, y + 38, 15777856, alpha);
            float tx = cx;
            if (icon.isPresent()) {
               WordIcons.draw(g, icon.get(), x0 + 8, y + 3, 2.0F);
               tx = cx + 20.0F;
            }

            g.text(font, title, (int)(tx - font.width(title) / 2.0F), y + 5, alpha << 24 | 15119394, true);
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(tx, y + 17);
            pose.scale(1.5F, 1.5F);
            g.text(font, line, -font.width(line) / 2, 0, alpha << 24 | 16769162, true);
            pose.popMatrix();
         }
      }
   }

   public static void showHeld(Phrase p) {
      heldLabel = p;
      heldLabelAt = System.currentTimeMillis();
   }

   private static void renderHeldLabel(GuiGraphicsExtractor g, Minecraft mc) {
      NarratorConfig c = ZhNarratorClient.config;
      Phrase p = heldLabel;
      if (p != null && c.hudHeldLabel && System.currentTimeMillis() - heldLabelAt <= 2500L) {
         Font font = mc.font;
         Style kai = Style.EMPTY.withFont(new Resource(fontId()));
         Component text = Component.literal(p.labelWithReading()).withStyle(kai);
         long age = System.currentTimeMillis() - heldLabelAt;
         int alpha = age > 2000L ? Math.max(20, (int)(255L * (2500L - age) / 500L)) : 255;
         int w = font.width(text);
         int x0 = Math.round(g.guiWidth() / 2.0F - w / 2.0F) - 7;
         int y0 = g.guiHeight() - 75;
         panel(g, x0, y0, x0 + w + 14, y0 + 15, alpha);
         g.text(font, text, x0 + 7, y0 + 3, alpha << 24 | 16769162, true);
      }
   }

   public static Identifier fontId() {
      String configured = ZhNarratorClient.config.hudFont;
      PhraseBook book = ZhNarratorClient.phrases;
      if (configured != null && !configured.isEmpty() && !configured.equals("default") && !configured.equals("zhnarrator:kai")) {
         Identifier id = Identifier.tryParse(configured);
         return id != null ? id : Identifier.withDefaultNamespace("default");
      } else {
         return book != null ? book.defaultFont() : Identifier.withDefaultNamespace("default");
      }
   }

   private static int withAlpha(int argb, int alpha) {
      return alpha << 24 | argb & 16777215;
   }

   private record Line(Component text, int colour) {
   }
}
