package dev.ame.zhnarrator;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription.Resource;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The learning overview (key J): the words being learned with their streak, the mastered words with
 * their next review, and what is in the pledge.
 */
public final class LearningScreen extends Screen {
   private static final int ROW = 12;
   private static final int GOLD = -14467;
   private static final int GREY = -4473925;
   private static final int WHITE = -1;
   private final Style kai = Style.EMPTY.withFont(new Resource(NarratorHud.fontId()));
   private final List<Map.Entry<String, Progress.Card>> active = new ArrayList<>();
   private final List<Map.Entry<String, Progress.Card>> mastered = new ArrayList<>();
   private final List<ItemStack> pledge = new ArrayList<>();

   public LearningScreen() {
      super(Component.translatable("zhnarrator.overview.title"));
   }

   protected void init() {
      Progress progress = ZhNarratorClient.progress;
      this.active.clear();
      this.mastered.clear();

      for (Map.Entry<String, Progress.Card> e : progress.cards().entrySet()) {
         (e.getValue().mastered ? this.mastered : this.active).add(e);
      }

      this.active.sort(Comparator.comparingLong(e -> e.getValue().since));
      this.mastered.sort(Comparator.comparingLong(e -> e.getValue().dueAt));
      this.pledge.clear();
      if (this.minecraft.level != null) {
         var ops = this.minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);

         for (String json : progress.world().pledge) {
            try {
               ItemStack.CODEC.parse(ops, JsonParser.parseString(json)).result().ifPresent(this.pledge::add);
            } catch (RuntimeException var6) {
               ZhNarratorClient.LOGGER.warn("Could not show a pledged item: {}", var6.toString());
            }
         }
      }
   }

   public boolean keyPressed(KeyEvent event) {
      if (ZhNarratorClient.keyOverview != null && ZhNarratorClient.keyOverview.matches(event)) {
         this.onClose();
         return true;
      } else {
         return super.keyPressed(event);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      int gap = 10;
      int colW = Math.max(120, (this.width - 2 * 16 - 2 * gap) / 3);
      int top = 34;
      int bottom = this.height - 12;
      int x0 = (this.width - (3 * colW + 2 * gap)) / 2;
      g.centeredText(this.font, this.title.getString(), this.width / 2, 8, GOLD);
      g.centeredText(
         this.font,
         Component.translatable(ZhNarratorClient.progress.sharedLearning() ? "zhnarrator.overview.shared" : "zhnarrator.overview.own").getString(),
         this.width / 2,
         19,
         GREY
      );
      long play = ZhNarratorClient.progress.playMs;
      int due = 0;

      for (Map.Entry<String, Progress.Card> e : this.mastered) {
         if (e.getValue().dueAt <= play) {
            due++;
         }
      }

      this.column(g, x0, top, colW, bottom, Component.translatable("zhnarrator.overview.active", new Object[]{this.active.size()}), this.activeLines());
      this.column(
         g,
         x0 + colW + gap,
         top,
         colW,
         bottom,
         Component.translatable("zhnarrator.overview.mastered", new Object[]{this.mastered.size(), due}),
         this.masteredLines(play)
      );
      this.pledgeColumn(g, x0 + 2 * (colW + gap), top, colW, bottom);
   }

   private List<Component> activeLines() {
      List<Component> lines = new ArrayList<>();
      int needed = Math.max(1, ZhNarratorClient.config.masterAfterCorrect);

      for (Map.Entry<String, Progress.Card> e : this.active) {
         Progress.Card card = e.getValue();
         String dots = "●".repeat(Math.min(card.streak, needed)) + "○".repeat(Math.max(0, needed - card.streak));
         Component line = this.word(e.getKey()).copy().append(Component.literal("  §e" + dots));
         lines.add(card.toneTrouble >= 2 ? line.copy().append(Component.literal(" §c♪")) : line);
      }

      return lines;
   }

   private List<Component> masteredLines(long play) {
      List<Component> lines = new ArrayList<>();

      for (Map.Entry<String, Progress.Card> e : this.mastered) {
         long left = e.getValue().dueAt - play;
         String when = left <= 0L ? "§a" + Component.translatable("zhnarrator.overview.due").getString() : "§7" + duration(left);
         lines.add(this.word(e.getKey()).copy().append(Component.literal("  " + when)));
      }

      return lines;
   }

   /** A word as 泥土 ní tǔ — dirt. */
   private Component word(String key) {
      PhraseBook book = ZhNarratorClient.phrases;
      Optional<Phrase> p = book == null
         ? Optional.empty()
         : book.fixed("hold." + key).or(() -> book.fixed("word." + key)).or(() -> book.fixed(key));
      if (p.isEmpty()) {
         return Component.literal(key);
      } else {
         Phrase phrase = p.get();
         String en = phrase.en().replaceAll("[.!]$", "");
         return Component.literal(phrase.bare())
            .withStyle(this.kai)
            .append(Component.literal(phrase.hasReading() ? " " + phrase.reading() : "").withStyle(this.kai.withColor(11591935)))
            .append(Component.literal(en.equals(phrase.text()) ? "" : " §7— " + en));
      }
   }

   private static String duration(long ms) {
      long minutes = Math.max(1L, ms / 60000L);
      if (minutes < 60L) {
         return minutes + " min";
      } else {
         return minutes < 1440L ? minutes / 60L + " h" : minutes / 1440L + " d";
      }
   }

   private void column(GuiGraphicsExtractor g, int x, int top, int w, int bottom, Component heading, List<Component> lines) {
      NarratorHud.panel(g, x - 4, top - 4, x + w + 4, bottom, 255);
      g.text(this.font, heading, x, top, GOLD, true);
      int y = top + ROW + 4;
      int shown = 0;

      for (Component line : lines) {
         if (y + ROW > bottom - ROW) {
            g.text(this.font, Component.translatable("zhnarrator.overview.more", new Object[]{lines.size() - shown}), x, y, GREY, true);
            return;
         }

         List<FormattedCharSequence> cut = this.font.split(line, w);
         if (!cut.isEmpty()) {
            g.text(this.font, cut.getFirst(), x, y, WHITE, true);
         }

         y += ROW;
         shown++;
      }

      if (lines.isEmpty()) {
         g.text(this.font, Component.translatable("zhnarrator.overview.none"), x, y, GREY, true);
      }
   }

   private void pledgeColumn(GuiGraphicsExtractor g, int x, int top, int w, int bottom) {
      NarratorHud.panel(g, x - 4, top - 4, x + w + 4, bottom, 255);
      g.text(this.font, Component.translatable("zhnarrator.overview.pledge", new Object[]{this.pledge.size()}), x, top, GOLD, true);
      int y = top + ROW + 4;
      if (this.pledge.isEmpty()) {
         g.text(this.font, Component.translatable("zhnarrator.overview.pledge_empty"), x, y, GREY, true);
      } else {
         List<FormattedCharSequence> hint = this.font.split(Component.translatable("zhnarrator.overview.pledge_hint"), w);
         for (FormattedCharSequence line : hint) {
            g.text(this.font, line, x, y, GREY, true);
            y += ROW - 2;
         }

         y += 4;
         int shown = 0;

         for (ItemStack stack : this.pledge) {
            if (y + 16 > bottom - ROW) {
               g.text(this.font, Component.translatable("zhnarrator.overview.more", new Object[]{this.pledge.size() - shown}), x, y, GREY, true);
               return;
            }

            WordIcons.draw(g, stack, x, y - 3, 0.75F);
            Component name = Component.literal(stack.getCount() + "× ").append(stack.getHoverName());
            List<FormattedCharSequence> cut = this.font.split(name, w - 16);
            if (!cut.isEmpty()) {
               g.text(this.font, cut.getFirst(), x + 16, y, WHITE, true);
            }

            y += 14;
            shown++;
         }
      }
   }

   public boolean isPauseScreen() {
      return true;
   }
}
