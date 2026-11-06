package dev.ame.zhnarrator;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription.Resource;
import org.joml.Matrix3x2fStack;

public final class QuizScreen extends Screen {
   private static final int BUTTON_W = 190;
   private static final int BUTTON_H = 22;
   private static final int ROW_H = 44;
   private int colW = 190;
   private final QuizManager quiz;
   private final Phrase question;
   private final List<Phrase> options;
   private final long deadline;
   private final long totalMs;
   private final Style kai;
   private final long acceptAfter;
   private boolean answered;
   private final boolean listening;
   private final String titleKey;
   private final String titleArg;

   public QuizScreen(QuizManager quiz, Phrase question, List<Phrase> options, float seconds, String titleKey, String titleArg) {
      super(Component.translatable("zhnarrator.quiz.title"));
      this.quiz = quiz;
      this.question = question;
      this.options = options;
      this.totalMs = (long)(seconds * 1000.0F);
      this.kai = Style.EMPTY.withFont(new Resource(NarratorHud.fontId()));
      this.deadline = System.currentTimeMillis() + this.totalMs;
      this.acceptAfter = System.currentTimeMillis() + 700L;
      this.titleKey = titleKey == null || titleKey.isEmpty() ? "zhnarrator.quiz.prompt" : titleKey;
      // A listening question plays the sentence and hides its English.
      this.listening = question.en().isBlank() || question.en().equals(question.text()) || this.titleKey.equals("zhnarrator.quiz.listen");
      this.titleArg = titleArg == null ? "" : titleArg;
   }

   private int buttonX(int i) {
      return this.width / 2 - this.colW - 6 + i % 2 * (this.colW + 12);
   }

   private int buttonY(int i) {
      return this.height / 2 + 2 + i / 2 * 44;
   }

   /** Pinyin under the answers, except for well known words: then the characters have to be read. */
   private boolean showReadings() {
      return this.question.hasReading()
         && !ZhNarratorClient.progress.pinyinHidden(Progress.cardKey(this.question));
   }

   private Component reading(Phrase option) {
      return NarratorHud.colouredComponent(option, true, Style.EMPTY, 11591935, 0.2F);
   }

   protected void init() {
      int widest = 0;
      if (this.showReadings()) {
         for (Phrase option : this.options) {
            widest = Math.max(widest, this.font.width(this.reading(option)) + 8);
         }
      }

      this.colW = Math.max(190, Math.min(widest, (this.width - 32 - 12) / 2));

      for (int i = 0; i < this.options.size(); i++) {
         int index = i;
         this.addRenderableWidget(Button.builder(Component.empty(), b -> this.choose(index)).bounds(this.buttonX(i), this.buttonY(i), this.colW, 22).build());
      }

      if (this.listening) {
         this.replay();
         this.addRenderableWidget(
            Button.builder(Component.translatable("zhnarrator.quiz.replay"), b -> this.replay())
               .bounds(this.width / 2 - 60, this.height / 2 - 54, 120, 20)
               .build()
         );
      }
   }

   private void replay() {
      if (ZhNarratorClient.speaker != null) {
         ZhNarratorClient.speaker.sayNow(this.question);
      }
   }

   public void tick() {
      if (!this.answered && System.currentTimeMillis() >= this.deadline) {
         this.finish(false);
      }
   }

   public boolean keyPressed(KeyEvent event) {
      int k = event.key();
      if (k >= 30 && k <= 33) {
         this.choose(k - 30);
         return true;
      } else {
         return super.keyPressed(event);
      }
   }

   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
      super.extractRenderState(g, mouseX, mouseY, a);
      float cx = this.width / 2.0F;
      int cardW = this.colW * 2 + 12 + 32;
      int x0 = Math.round(cx - cardW / 2.0F);
      int top = this.height / 2 - 130;
      boolean readings = this.showReadings();
      int bottom = this.buttonY(3) + 22 + (readings ? 3 + 9 : 0) + 10;
      NarratorHud.panel(g, x0, top, x0 + cardW, bottom, 255);
      if (ZhNarratorClient.config.quizShowIcons) {
         WordIcons.iconFor(this.question).ifPresent(stack -> {
            int sx = Math.round(cx) - 28;
            int sy = this.height / 2 - 123;
            g.fill(sx, sy, sx + 56, sy + 56, 1342177280);
            NarratorHud.frame(g, sx, sy, sx + 56, sy + 56, 1088861218);
            WordIcons.draw(g, stack, sx + 4, sy + 4, 3.0F);
         });
      }

      Component title = this.listening
         ? Component.translatable("zhnarrator.quiz.listen")
         : Component.translatable(this.titleKey, new Object[]{Component.literal(this.titleArg).withStyle(this.kai)});
      g.text(this.font, title, (int)(cx - this.font.width(title) / 2.0F), this.height / 2 - 62, -4473925, true);
      Matrix3x2fStack pose = g.pose();
      if (!this.listening) {
         Component prompt = Component.literal(this.question.en());
         pose.pushMatrix();
         pose.translate(cx, this.height / 2.0F - 48.0F);
         pose.scale(1.6F, 1.6F);
         g.text(this.font, prompt, -this.font.width(prompt) / 2, 0, -8054, true);
         pose.popMatrix();
      }

      long left = Math.max(0L, this.deadline - System.currentTimeMillis());
      Component timer = Component.literal(String.format("%.0f s", left / 1000.0));
      int barRight = x0 + cardW - 16 - this.font.width(timer) - 6;
      NarratorHud.timeBar(g, x0 + 16, this.height / 2 - 20, barRight - (x0 + 16), this.totalMs <= 0L ? 0.0F : (float)left / (float)this.totalMs, 255);
      g.text(this.font, timer, barRight + 6, this.height / 2 - 24, left < 3000L ? -34182 : -4473925, true);

      for (int i = 0; i < this.options.size(); i++) {
         Component num = Component.literal(i + 1 + ".").withStyle(this.kai);
         Component zh = NarratorHud.colouredComponent(this.options.get(i), false, this.kai, 16777215, 0.35F);
         int plainW = this.font.width(num) + 6 + this.font.width(zh);
         float s = Math.min(1.5F, (float)(this.colW - 12) / plainW);
         pose.pushMatrix();
         pose.translate(this.buttonX(i) + this.colW / 2.0F - plainW * s / 2.0F, this.buttonY(i) + (22.0F - 9.0F * s) / 2.0F);
         pose.scale(s, s);
         g.text(this.font, num, 0, 0, -8054, true);
         g.text(this.font, zh, this.font.width(num) + 6, 0, -1, true);
         pose.popMatrix();
         if (readings) {
            Component py = this.reading(this.options.get(i));
            float ps = Math.min(1.0F, (float)(this.colW + 8) / this.font.width(py));
            pose.pushMatrix();
            pose.translate(this.buttonX(i) + this.colW / 2.0F - this.font.width(py) * ps / 2.0F, this.buttonY(i) + 22 + 3);
            pose.scale(ps, ps);
            g.text(this.font, py, 0, 0, -1, true);
            pose.popMatrix();
         }
      }
   }

   private void choose(int index) {
      if (!this.answered && index < this.options.size() && System.currentTimeMillis() >= this.acceptAfter) {
         this.finish(this.options.get(index) == this.question);
      }
   }

   private void finish(boolean correct) {
      this.answered = true;
      this.minecraft.gui.setScreen(null);
      this.quiz.answer(this.minecraft, this.question, correct);
   }

   public void onClose() {
      if (!this.answered) {
         this.quiz.suspend(this.question, this.options, Math.max(1000L, this.deadline - System.currentTimeMillis()), this.titleKey, this.titleArg);
         this.minecraft.gui.setScreen(new PauseScreen(true));
      } else {
         super.onClose();
      }
   }

   public boolean isPauseScreen() {
      return ZhNarratorClient.config.quizPauseGame;
   }
}
