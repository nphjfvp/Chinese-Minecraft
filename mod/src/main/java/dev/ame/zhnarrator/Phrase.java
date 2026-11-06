package dev.ame.zhnarrator;

import java.util.List;

public record Phrase(String id, String action, String key, String text, String reading, String en, List<Phrase.Part> parts) {
   public boolean isMovement() {
      String var1 = this.action;

      return switch (var1) {
         case "walk", "sprint", "sneak", "swim", "fly", "climb" -> true;
         default -> false;
      };
   }

   public String soundPath(String voice, String speed) {
      return voice + "." + speed + "." + this.id;
   }

   public boolean hasReading() {
      return !this.reading.isEmpty();
   }

   public String bare() {
      return bare(this.text);
   }

   static String bare(String text) {
      String t = text;

      while (!t.isEmpty() && "。！？.!?".indexOf(t.charAt(t.length() - 1)) >= 0) {
         t = t.substring(0, t.length() - 1);
      }

      while (!t.isEmpty() && "¡¿".indexOf(t.charAt(0)) >= 0) {
         t = t.substring(1);
      }

      return t;
   }

   public String labelWithReading() {
      return this.hasReading() ? this.bare() + "  " + this.reading : this.bare();
   }

   public record Part(String text, String reading, String role) {
   }
}
