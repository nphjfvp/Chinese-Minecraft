package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription.Resource;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;

/**
 * The sentence history (key U): pauses the game and lists what the teacher said lately, newest on top,
 * with pinyin and English. Hovering a word shows its own translation.
 */
public final class HistoryScreen extends Screen {
   private static final float ZH_SCALE = 1.5F;
   private static final int ENTRY_H = 42;
   private static final int LEFT = 24;
   private static final int TOP = 40;
   private final Style kai = Style.EMPTY.withFont(new Resource(NarratorHud.fontId()));
   private List<Phrase> sentences = List.of();
   private int scroll;

   public HistoryScreen() {
      super(Component.translatable("zhnarrator.history.title"));
   }

   protected void init() {
      this.sentences = ZhNarratorClient.speaker == null ? List.of() : ZhNarratorClient.speaker.history();
      this.scroll = Math.min(this.scroll, Math.max(0, this.sentences.size() - 1));
   }

   private int visible() {
      return Math.max(1, (this.height - TOP - 12) / ENTRY_H);
   }

   public boolean mouseScrolled(double x, double y, double dx, double dy) {
      int max = Math.max(0, this.sentences.size() - this.visible());
      this.scroll = Math.max(0, Math.min(max, this.scroll - (int)Math.signum(dy)));
      return true;
   }

   public boolean keyPressed(KeyEvent event) {
      if (ZhNarratorClient.keyHistory != null && ZhNarratorClient.keyHistory.matches(event)) {
         this.onClose();
         return true;
      } else {
         return super.keyPressed(event);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      NarratorConfig c = ZhNarratorClient.config;
      g.centeredText(this.font, this.title.getString(), this.width / 2, 10, -14467);
      g.centeredText(this.font, Component.translatable("zhnarrator.history.hint").getString(), this.width / 2, 22, -4473925);
      if (this.sentences.isEmpty()) {
         g.centeredText(this.font, Component.translatable("zhnarrator.history.empty").getString(), this.width / 2, TOP + 10, -4473925);
      } else {
         Hover hover = null;
         int y = TOP;
         int end = Math.min(this.sentences.size(), this.scroll + this.visible());

         for (int i = this.scroll; i < end; i++) {
            Hover h = this.entry(g, this.sentences.get(i), LEFT, y, mouseX, mouseY, c);
            if (h != null) {
               hover = h;
            }

            y += ENTRY_H;
         }

         if (this.sentences.size() > this.visible()) {
            String pos = (this.scroll + 1) + "–" + end + " / " + this.sentences.size();
            g.text(this.font, pos, this.width - 12 - this.font.width(pos), 10, -4473925, true);
         }

         if (hover != null) {
            g.setTooltipForNextFrame(hover.lines(), mouseX, mouseY);
         }
      }
   }

   /** Draws one sentence; returns the tooltip of the word under the mouse, if any. */
   private Hover entry(GuiGraphicsExtractor g, Phrase p, int x, int y, int mouseX, int mouseY, NarratorConfig c) {
      NarratorHud.panel(g, x - 8, y - 4, this.width - 16, y + ENTRY_H - 6, 255);
      List<Phrase.Part> parts = p.parts().isEmpty() ? List.of(new Phrase.Part(p.bare(), p.reading(), "")) : p.parts();
      String sep = ZhNarratorClient.phrases == null ? "" : ZhNarratorClient.phrases.meta().wordSeparator();
      boolean learning = ZhNarratorClient.progress != null && ZhNarratorClient.progress.isActive(p);
      Hover hover = null;
      float px = x;
      if (learning) {
         g.text(this.font, "★", x, y + 3, -10944, true);
         px += this.font.width("★") + 4;
      }

      Matrix3x2fStack pose = g.pose();

      for (int i = 0; i < parts.size(); i++) {
         Phrase.Part part = parts.get(i);
         String shown = part.text() + (i < parts.size() - 1 ? sep : tail(p, parts));
         Component piece = Component.literal(shown).withStyle(this.kai);
         float w = this.font.width(piece) * ZH_SCALE;
         boolean over = mouseX >= px && mouseX < px + w && mouseY >= y && mouseY < y + 9 * ZH_SCALE;
         if (over) {
            g.fill(Math.round(px) - 1, y - 1, Math.round(px + w) + 1, Math.round(y + 9 * ZH_SCALE) + 1, 0x55FFFFFF);
            hover = this.tooltip(part);
         }

         pose.pushMatrix();
         pose.translate(px, y);
         pose.scale(ZH_SCALE, ZH_SCALE);
         g.text(this.font, piece, 0, 0, colour(part.role(), c), true);
         pose.popMatrix();
         px += w;
      }

      int line = y + Math.round(9 * ZH_SCALE) + 3;
      if (p.hasReading()) {
         g.text(this.font, Component.literal(p.reading()).withStyle(this.kai), x, line, c.hudColorPinyin, true);
      }

      if (!p.en().isBlank() && !p.en().equals(p.text())) {
         g.text(this.font, Component.literal(p.en()), x, line + 11, c.hudColorEn, true);
      }

      return hover;
   }

   /** Whatever comes after the parts in the text, such as the full stop. */
   private static String tail(Phrase p, List<Phrase.Part> parts) {
      StringBuilder joined = new StringBuilder();
      String sep = ZhNarratorClient.phrases == null ? "" : ZhNarratorClient.phrases.meta().wordSeparator();

      for (int i = 0; i < parts.size(); i++) {
         joined.append(parts.get(i).text()).append(i < parts.size() - 1 ? sep : "");
      }

      return p.text().startsWith(joined.toString()) ? p.text().substring(joined.length()) : "";
   }

   private Hover tooltip(Phrase.Part part) {
      List<FormattedCharSequence> lines = new ArrayList<>();
      String word = part.text().replaceAll("[，。！？,.!?、]+$", "");
      lines.add(
         Component.literal(word)
            .withStyle(this.kai.withColor(16769162))
            .append(Component.literal(part.reading().isEmpty() ? "" : "  " + part.reading()).withStyle(this.kai.withColor(11591935)))
            .getVisualOrderText()
      );
      String gloss = ZhNarratorClient.phrases == null ? null : ZhNarratorClient.phrases.gloss(word).orElse(null);
      lines.add(Component.literal(gloss == null ? "?" : gloss).getVisualOrderText());
      String role = switch (part.role()) {
         case "pron" -> "pronoun";
         case "verb" -> "verb";
         case "obj" -> "noun";
         case "adj" -> "adjective";
         case "num" -> "number";
         case "part" -> "particle";
         default -> "";
      };
      if (!role.isEmpty()) {
         lines.add(Component.literal("§7" + role).getVisualOrderText());
      }

      return new Hover(lines);
   }

   private static int colour(String role, NarratorConfig c) {
      return switch (role) {
         case "pron" -> c.hudColorPronoun;
         case "verb" -> c.hudColorVerb;
         case "obj" -> c.hudColorObject;
         case "adj" -> c.hudColorAdjective;
         case "num" -> c.hudColorNumber;
         case "part" -> c.hudColorParticle;
         default -> c.hudColorZh;
      };
   }

   public boolean isPauseScreen() {
      return true;
   }

   private record Hover(List<FormattedCharSequence> lines) {
   }
}
