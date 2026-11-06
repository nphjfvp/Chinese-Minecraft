package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

public final class WordIcons {
   private static final Map<String, Optional<ItemStack>> CACHE = new HashMap<>();
   private static int debugLogs;

   private WordIcons() {
   }

   static void clear() {
      CACHE.clear();
   }

   public static Optional<ItemStack> iconFor(Phrase p) {
      return p.key() != null && ZhNarratorClient.phrases != null ? CACHE.computeIfAbsent(p.key(), WordIcons::find) : Optional.empty();
   }

   private static Optional<ItemStack> find(String key) {
      PhraseBook phrases = ZhNarratorClient.phrases;

      for (Item item : BuiltInRegistries.ITEM) {
         String path = BuiltInRegistries.ITEM.getKey(item).getPath();
         if (phrases.keyFor("item", path).filter(key::equals).isPresent() || phrases.keyFor("block", path).filter(key::equals).isPresent()) {
            return Optional.of(new ItemStack(item));
         }
      }

      for (String kind : List.of("item", "block")) {
         for (String pattern : phrases.patternsOf(kind, key)) {
            if (!pattern.contains("*")) {
               for (Item itemx : BuiltInRegistries.ITEM) {
                  if (BuiltInRegistries.ITEM.getKey(itemx).getPath().equals(pattern)) {
                     return Optional.of(new ItemStack(itemx));
                  }
               }
            }
         }
      }

      List<String> candidates = new ArrayList<>();
      candidates.add(key.endsWith("_sheep") ? "sheep" : key);

      for (String patternx : phrases.patternsOf("entity", key)) {
         if (!patternx.contains("*")) {
            candidates.add(patternx);
         }
      }

      for (Item itemxx : BuiltInRegistries.ITEM) {
         String path = BuiltInRegistries.ITEM.getKey(itemxx).getPath();

         for (String c : candidates) {
            if (path.equals(c + "_spawn_egg")) {
               return Optional.of(new ItemStack(itemxx));
            }
         }
      }

      return Optional.empty();
   }

   public static void draw(GuiGraphicsExtractor g, ItemStack stack, float x, float y, float scale) {
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(x, y);
      pose.scale(scale, scale);
      CharacterIcons.suppressed = true;

      try {
         g.item(stack, 0, 0);
         if (ZhNarratorClient.DEBUG_PROMPT && debugLogs++ < 3) {
            ZhNarratorClient.LOGGER.info("debug: drew {} at {},{} scale {}", new Object[]{stack.getItem(), x, y, scale});
         }
      } finally {
         CharacterIcons.suppressed = false;
      }

      pose.popMatrix();
   }
}
