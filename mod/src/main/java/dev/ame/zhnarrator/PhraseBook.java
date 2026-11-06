package dev.ame.zhnarrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class PhraseBook {
   private final LanguagePacks.Meta meta;
   private final Map<String, Phrase> phrasesById = new HashMap<>();
   private final Map<String, List<PhraseBook.Word>> wordsByKind = new HashMap<>();
   private final Map<String, Optional<String>> keyCache = new HashMap<>();
   private final Map<String, String> measureByKey = new HashMap<>();
   private final Map<String, List<String>> patternsByKindKey = new HashMap<>();
   private final List<String> hintLanguages = new ArrayList<>();
   private final List<String> voices = new ArrayList<>();
   private final List<String> speeds = new ArrayList<>();
   private final Map<String, String> lipsync = new HashMap<>();
   private int lipsyncWindowMs = 40;

   PhraseBook(LanguagePacks.Meta meta, JsonObject root) {
      this.meta = meta;

      for (JsonElement e : root.getAsJsonArray("voices")) {
         this.voices.add(e.getAsString());
      }

      for (JsonElement e : root.getAsJsonArray("speeds")) {
         this.speeds.add(e.getAsString());
      }

      for (JsonElement e : root.getAsJsonArray("words")) {
         JsonObject o = e.getAsJsonObject();
         List<Pattern> patterns = new ArrayList<>();
         List<String> raw = new ArrayList<>();

         for (JsonElement p : o.getAsJsonArray("patterns")) {
            patterns.add(globToRegex(p.getAsString()));
            raw.add(p.getAsString());
         }

         String measure = o.has("measure") ? o.get("measure").getAsString() : "ge";
         PhraseBook.Word word = new PhraseBook.Word(o.get("key").getAsString(), o.get("kind").getAsString(), patterns, measure);
         this.wordsByKind.computeIfAbsent(word.kind(), k -> new ArrayList<>()).add(word);
         this.measureByKey.put(word.key(), measure);
         this.patternsByKindKey.put(word.kind() + ":" + word.key(), raw);
      }

      if (root.has("meta") && root.getAsJsonObject("meta").has("hintLanguages")) {
         for (JsonElement e : root.getAsJsonObject("meta").getAsJsonArray("hintLanguages")) {
            this.hintLanguages.add(e.getAsString());
         }
      }

      JsonArray phrases = root.getAsJsonArray("phrases");
      String hintLang = gameLanguage();

      for (JsonElement e : phrases) {
         JsonObject o = e.getAsJsonObject();
         String key = o.get("key").isJsonNull() ? null : o.get("key").getAsString();
         String en = o.get("en").getAsString();
         if (o.has("hints") && o.get("hints").isJsonObject() && o.getAsJsonObject("hints").has(hintLang)) {
            en = o.getAsJsonObject("hints").get(hintLang).getAsString();
         }

         List<Phrase.Part> parts = new ArrayList<>();
         if (o.has("parts")) {
            for (JsonElement pe : o.getAsJsonArray("parts")) {
               JsonObject po = pe.getAsJsonObject();
               parts.add(new Phrase.Part(po.get("text").getAsString(), optional(po, "reading"), po.get("role").getAsString()));
            }
         }

         Phrase p = new Phrase(o.get("id").getAsString(), o.get("action").getAsString(), key, o.get("text").getAsString(), optional(o, "reading"), en, parts);
         this.phrasesById.put(p.id(), p);
      }
   }

   void lipsync(JsonObject root) {
      this.lipsyncWindowMs = root.has("window") ? root.get("window").getAsInt() : 40;

      for (Entry<String, JsonElement> e : root.getAsJsonObject("clips").entrySet()) {
         this.lipsync.put(e.getKey(), e.getValue().getAsString());
      }
   }

   public int mouth(String clip, long ms) {
      String levels = this.lipsync.get(clip);
      if (levels == null) {
         return -1;
      } else {
         int i = (int)(ms / this.lipsyncWindowMs);
         return i >= 0 && i < levels.length() ? levels.charAt(i) - 48 : 0;
      }
   }

   public long spokenMs(String clip) {
      String levels = this.lipsync.get(clip);
      if (levels == null) {
         return -1L;
      } else {
         int last = levels.length() - 1;

         while (last >= 0 && levels.charAt(last) == '0') {
            last--;
         }

         return (long)(last + 1) * this.lipsyncWindowMs;
      }
   }

   private static String optional(JsonObject o, String key) {
      return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }

   public LanguagePacks.Meta meta() {
      return this.meta;
   }

   public String namespace() {
      return this.meta.namespace();
   }

   public Identifier portrait() {
      return Identifier.fromNamespaceAndPath(this.meta.namespace(), "textures/gui/teacher.png");
   }

   public Identifier defaultFont() {
      Identifier id = Identifier.tryParse(this.meta.font());
      return id != null ? id : Identifier.withDefaultNamespace("default");
   }

   public List<String> hintLanguages() {
      return this.hintLanguages;
   }

   private static String gameLanguage() {
      String chosen = ZhNarratorClient.config == null ? null : ZhNarratorClient.config.hintLanguage;
      if (chosen != null && !chosen.isBlank() && !chosen.equals("auto")) {
         return chosen;
      } else {
         try {
            String code = Minecraft.getInstance().getLanguageManager().getSelected();
            return code.contains("_") ? code.substring(0, code.indexOf(95)) : code;
         } catch (RuntimeException var2) {
            return "en";
         }
      }
   }

   public String measureOf(String key) {
      return this.measureByKey.getOrDefault(key, "ge");
   }

   public Optional<Phrase> countUnit(String action, int n, String measure) {
      return this.fixed(action + "." + n + "." + measure);
   }

   public List<String> voices() {
      return this.voices;
   }

   public List<String> speeds() {
      return this.speeds;
   }

   public int size() {
      return this.phrasesById.size();
   }

   public Collection<Phrase> all() {
      return this.phrasesById.values();
   }

   public List<Phrase> byAction(String action) {
      List<Phrase> out = new ArrayList<>();

      for (Phrase p : this.phrasesById.values()) {
         if (p.action().equals(action)) {
            out.add(p);
         }
      }

      return out;
   }

   public Optional<Phrase> fixed(String id) {
      return Optional.ofNullable(this.phrasesById.get(id));
   }

   public Optional<Phrase> forBlock(String action, String blockPath) {
      return this.keyFor("block", blockPath).flatMap(k -> this.fixed(action + "." + k));
   }

   public Optional<Phrase> forEntity(String action, String entityPath) {
      return this.keyFor("entity", entityPath).flatMap(k -> this.fixed(action + "." + k));
   }

   public Optional<Phrase> forItem(String action, String itemPath) {
      Optional<Phrase> asItem = this.keyFor("item", itemPath).flatMap(k -> this.fixed(action + "." + k));
      return asItem.isPresent() ? asItem : this.forBlock(action, itemPath);
   }

   public Optional<Phrase> word(String kind, String path) {
      return this.keyFor(kind, path).flatMap(k -> this.fixed("word." + k));
   }

   public List<String> patternsOf(String kind, String key) {
      return this.patternsByKindKey.getOrDefault(kind + ":" + key, List.of());
   }

   public Optional<String> keyFor(String kind, String path) {
      return this.keyCache.computeIfAbsent(kind + ":" + path, ignored -> {
         for (PhraseBook.Word w : this.wordsByKind.getOrDefault(kind, List.of())) {
            if (w.matches(path)) {
               return Optional.of(w.key());
            }
         }

         return Optional.empty();
      });
   }

   public static boolean isBareWord(Phrase p) {
      return p.action().equals("hold") || p.action().equals("word") || p.action().equals("noun") || p.action().equals("plural");
   }

   public static boolean isFragment(Phrase p) {
      return p.action().equals("word") || p.action().equals("noun") || p.action().equals("plural") || p.action().endsWith("count");
   }

   private static Pattern globToRegex(String glob) {
      StringBuilder sb = new StringBuilder("^");

      for (String part : glob.split("\\*", -1)) {
         sb.append(Pattern.quote(part)).append(".*");
      }

      sb.setLength(sb.length() - 2);
      return Pattern.compile(sb.append('$').toString());
   }

   private record Word(String key, String kind, List<Pattern> patterns, String measure) {
      boolean matches(String path) {
         for (Pattern p : this.patterns) {
            if (p.matcher(path).matches()) {
               return true;
            }
         }

         return false;
      }
   }
}
