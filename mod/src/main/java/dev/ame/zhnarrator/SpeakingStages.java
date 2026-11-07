package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Speaking in three stages: first slowly, word by word; then a bit faster; then at normal speed.
 * Each sentence remembers the stage it reached, so practice goes on from there next time.
 */
final class SpeakingStages {
   static final int SLOW = 0;
   static final int FASTER = 1;
   static final int NORMAL = 2;
   /** Quiet that ends a take: long when speaking word by word, otherwise the helper's default. */
   static final float SLOW_PAUSE_SECONDS = 2.5F;

   private SpeakingStages() {
   }

   /**
    * Whether each word (part) of the sentence was said right, from the helper's similarity per syllable
    * (1 = right). A word is right when all its syllables are. Empty when the syllables cannot be matched
    * to the words.
    */
   static List<Boolean> wordResults(Phrase sentence, List<Float> perSyllable) {
      List<Boolean> out = new ArrayList<>();
      int at = 0;

      for (Phrase.Part part : sentence.parts()) {
         int syllables = hanCount(part.text());
         if (at + syllables > perSyllable.size()) {
            return List.of();
         }

         boolean right = true;

         for (int i = at; i < at + syllables; i++) {
            right &= perSyllable.get(i) >= 1.0F;
         }

         out.add(right);
         at += syllables;
      }

      return at == perSyllable.size() ? out : List.of();
   }

   private static int hanCount(String text) {
      return (int)text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
   }

   /**
    * The longest the sentence may take at a stage, in seconds, measured from the first to the last word;
    * 0 when there is no limit. {@code normalMs} and {@code slowMs} are the teacher's recordings (spoken
    * part only), -1 when unknown.
    */
   static float targetSeconds(int stage, long normalMs, long slowMs) {
      if (normalMs <= 0L) {
         return 0.0F;
      } else {
         return switch (stage) {
            case FASTER -> Math.max(normalMs * 2.2F, slowMs > 0L ? slowMs * 1.3F : 0.0F) / 1000.0F + 0.5F;
            case NORMAL -> normalMs * 1.5F / 1000.0F + 0.4F;
            default -> 0.0F;
         };
      }
   }

   static boolean fastEnough(int stage, float spanSeconds, long normalMs, long slowMs) {
      float target = targetSeconds(stage, normalMs, slowMs);
      return target <= 0.0F || spanSeconds < 0.0F || spanSeconds <= target;
   }
}
