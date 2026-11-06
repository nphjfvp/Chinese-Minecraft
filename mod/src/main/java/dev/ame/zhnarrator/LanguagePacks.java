package dev.ame.zhnarrator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class LanguagePacks implements ResourceManagerReloadListener {
   public static final Identifier ID = Identifier.fromNamespaceAndPath("zhnarrator", "language_packs");
   static final String NAMESPACE_PREFIX = "zhnarrator_";
   private final TreeMap<String, LanguagePacks.Meta> available = new TreeMap<>();
   private ResourceManager manager;

   public void onResourceManagerReload(ResourceManager manager) {
      this.manager = manager;
      this.available.clear();

      for (String namespace : manager.getNamespaces()) {
         if (namespace.startsWith("zhnarrator_")) {
            Identifier file = Identifier.fromNamespaceAndPath(namespace, "phrases.json");
            Optional<Resource> resource = manager.getResource(file);
            if (!resource.isEmpty()) {
               try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                  JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                  JsonObject meta = root.has("meta") ? root.getAsJsonObject("meta") : new JsonObject();
                  LanguagePacks.Meta m = LanguagePacks.Meta.from(namespace, meta);
                  this.available.put(m.language(), m);
               } catch (RuntimeException | IOException var12) {
                  ZhNarratorClient.LOGGER.warn("Language pack {} could not be read: {}", namespace, var12.toString());
               }
            }
         }
      }

      ZhNarratorClient.LOGGER.info("Language packs: {}", this.available.isEmpty() ? "none" : String.join(", ", this.available.keySet()));
      ZhNarratorClient.onLanguagePacksScanned(this);
   }

   public List<String> languages() {
      return Collections.unmodifiableList(new ArrayList<>(this.available.keySet()));
   }

   public Optional<LanguagePacks.Meta> meta(String language) {
      return Optional.ofNullable(this.available.get(language));
   }

   public boolean isEmpty() {
      return this.available.isEmpty();
   }

   public PhraseBook load(String language) throws IOException {
      LanguagePacks.Meta meta = this.available.get(language);
      if (meta != null && this.manager != null) {
         Identifier file = Identifier.fromNamespaceAndPath(meta.namespace(), "phrases.json");
         Resource resource = (Resource)this.manager.getResource(file).orElseThrow(() -> new IOException(file + " is missing"));
         InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8);

         PhraseBook book;
         try {
            book = new PhraseBook(meta, JsonParser.parseReader(reader).getAsJsonObject());
         } catch (Throwable var13) {
            try {
               reader.close();
            } catch (Throwable var11) {
               var13.addSuppressed(var11);
            }

            throw var13;
         }

         reader.close();
         Optional<Resource> lipsync = this.manager.getResource(Identifier.fromNamespaceAndPath(meta.namespace(), "lipsync.json"));
         if (lipsync.isPresent()) {
            try (InputStreamReader readerx = new InputStreamReader(lipsync.get().open(), StandardCharsets.UTF_8)) {
               book.lipsync(JsonParser.parseReader(readerx).getAsJsonObject());
            }
         }

         return book;
      } else {
         throw new IOException("no language pack for '" + language + "'");
      }
   }

   public record Meta(
      String language,
      String namespace,
      String name,
      String nativeName,
      String font,
      String wordSeparator,
      String sentenceEnd,
      boolean iconWords,
      boolean hasReading,
      String uiCorrect,
      String uiWrong,
      int portraitWidth,
      int portraitHeight,
      int portraitFrames,
      float portraitScreenHeight,
      int portraitNeck,
      int portraitWaist,
      float portraitHop,
      int shootDraw,
      int shootBang,
      String shootSound
   ) {
      static LanguagePacks.Meta from(String namespace, JsonObject o) {
         String language = namespace.substring("zhnarrator_".length());
         JsonObject ui = o.has("ui") ? o.getAsJsonObject("ui") : new JsonObject();
         JsonObject portrait = o.has("portrait") ? o.getAsJsonObject("portrait") : new JsonObject();
         int width = portrait.has("frameWidth") ? portrait.get("frameWidth").getAsInt() : 48;
         int height = portrait.has("frameHeight") ? portrait.get("frameHeight").getAsInt() : 56;
         int padBottom = portrait.has("padBottom") ? portrait.get("padBottom").getAsInt() : 0;
         return new LanguagePacks.Meta(
            language,
            namespace,
            str(o, "name", language),
            str(o, "nativeName", language),
            str(o, "font", "minecraft:default"),
            str(o, "wordSeparator", " "),
            str(o, "sentenceEnd", "."),
            o.has("iconWords") && o.get("iconWords").getAsBoolean(),
            o.has("hasReading") && o.get("hasReading").getAsBoolean(),
            str(ui, "correct", "✓"),
            str(ui, "wrong", "✗"),
            width,
            height,
            portrait.has("frames") ? portrait.get("frames").getAsInt() : 8,
            portrait.has("screenHeight") ? portrait.get("screenHeight").getAsFloat() : 56.0F,
            portrait.has("neck") ? portrait.get("neck").getAsInt() : 38 * height / 56,
            portrait.has("waist") ? portrait.get("waist").getAsInt() : height - padBottom,
            portrait.has("hop") ? portrait.get("hop").getAsFloat() : 6.0F,
            portrait.has("shoot") ? portrait.getAsJsonArray("shoot").get(0).getAsInt() : -1,
            portrait.has("shoot") ? portrait.getAsJsonArray("shoot").get(1).getAsInt() : -1,
            str(portrait, "shootSound", "")
         );
      }

      private static String str(JsonObject o, String key, String fallback) {
         return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
      }

      public String label() {
         return this.nativeName.equals(this.name) ? this.name : this.name + " (" + this.nativeName + ")";
      }
   }
}
