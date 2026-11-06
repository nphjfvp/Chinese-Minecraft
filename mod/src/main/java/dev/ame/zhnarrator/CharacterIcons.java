package dev.ame.zhnarrator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription.Resource;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

public final class CharacterIcons {
   private static final int SLOT = 16;
   static boolean suppressed;

   private CharacterIcons() {
   }

   public static boolean draw(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
      NarratorConfig c = ZhNarratorClient.config;
      if (!suppressed
         && !stack.isEmpty()
         && c.iconMode != null
         && !c.iconMode.equals("off")
         && ZhNarratorClient.phrases != null
         && ZhNarratorClient.phrases.meta().iconWords()) {
         Phrase word = ZhNarratorClient.phrases.forItem("hold", ActionDetector.itemPath(stack)).orElse(null);
         if (word != null && word.key() != null) {
            int correct = ZhNarratorClient.progress.wordCorrect(word.key());
            int needed = c.iconMode.equals("known") ? 1 : Math.max(1, c.learnedAfterCorrect);
            if (correct < needed) {
               return false;
            } else {
               String text = word.bare();
               Font font = Minecraft.getInstance().font;
               Style kai = Style.EMPTY.withFont(new Resource(NarratorHud.fontId()));
               Component label = Component.literal(text).withStyle(kai);
               int w = font.width(label);
               float scale = Math.min(15.0F / Math.max(1, w), 15.0F / 9.0F);
               scale = Math.min(scale, 1.45F);
               g.fill(x, y, x + 16, y + 16, 1711276032);
               Matrix3x2fStack pose = g.pose();
               pose.pushMatrix();
               pose.translate(x + 8.0F, y + 8.0F);
               pose.scale(scale, scale);
               g.text(font, label, -w / 2, -9 / 2, -8054, false);
               pose.popMatrix();
               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }
}
