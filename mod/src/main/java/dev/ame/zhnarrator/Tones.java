package dev.ame.zhnarrator;

import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.List;

/** Pinyin tone marks: which tone a syllable has, and the same syllable in the four tones. */
final class Tones {
   /** Combining marks for tones 1–4: macron, acute, caron, grave. */
   private static final char[] MARKS = {'̄', '́', '̌', '̀'};

   private Tones() {
   }

   /** The tone of a pinyin syllable, 1–4, or 0 for the neutral tone (no mark). */
   static int toneOf(String syllable) {
      String split = Normalizer.normalize(syllable, Form.NFD);

      for (int i = 0; i < MARKS.length; i++) {
         if (split.indexOf(MARKS[i]) >= 0) {
            return i + 1;
         }
      }

      return 0;
   }

   /** The syllable without its tone mark; ü keeps its dots. */
   static String bare(String syllable) {
      StringBuilder out = new StringBuilder();

      for (char ch : Normalizer.normalize(syllable, Form.NFD).toCharArray()) {
         if (ch != MARKS[0] && ch != MARKS[1] && ch != MARKS[2] && ch != MARKS[3]) {
            out.append(ch);
         }
      }

      return Normalizer.normalize(out.toString(), Form.NFC);
   }

   /**
    * The syllable with the given tone, 1–4. The mark goes on a or e if there is one, on the o of ou,
    * otherwise on the last vowel.
    */
   static String withTone(String syllable, int tone) {
      String bare = Normalizer.normalize(bare(syllable), Form.NFD);
      String lower = bare.toLowerCase();
      int at = lower.indexOf('a');
      if (at < 0) {
         at = lower.indexOf('e');
      }

      if (at < 0 && lower.contains("ou")) {
         at = lower.indexOf('o');
      }

      if (at < 0) {
         for (int i = lower.length() - 1; i >= 0; i--) {
            if ("aeiouv".indexOf(lower.charAt(i)) >= 0) {
               at = i;
               break;
            }
         }
      }

      if (at < 0 || tone < 1 || tone > 4) {
         return Normalizer.normalize(bare, Form.NFC);
      } else {
         // After the vowel, but after the dots of ü so that NFC makes ǖ ǘ ǚ ǜ.
         int insert = at + 1;
         if (insert < bare.length() && bare.charAt(insert) == '̈') {
            insert++;
         }

         return Normalizer.normalize(bare.substring(0, insert) + MARKS[tone - 1] + bare.substring(insert), Form.NFC);
      }
   }

   /** The syllable in all four tones, in order. */
   static List<String> allTones(String syllable) {
      List<String> out = new ArrayList<>();

      for (int tone = 1; tone <= 4; tone++) {
         out.add(withTone(syllable, tone));
      }

      return out;
   }
}
