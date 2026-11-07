package dev.ame.zhnarrator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Exports the words being learned and the mastered ones as Anki cards: a text file Anki imports (File →
 * Import) and the teacher's recordings. Front: the word in characters, with its sound. Back: pinyin,
 * English and a sentence heard in the game, with its sound. Importing again updates the cards.
 */
final class AnkiExport {
   static final String FILE = "learn-or-die-chinese.txt";

   /** One card; {@code sounds} are the phrase ids whose recordings it plays. */
   record Note(String guid, String front, String back, String tags, List<String> sounds) {
   }

   /** What the export did, for the chat message. */
   record Result(Path folder, int notes, int sounds, Optional<Path> ankiMedia, int ankiProfiles) {
   }

   private AnkiExport() {
   }

   static String soundFile(String phraseId) {
      return "lod_zh_" + phraseId.replaceAll("[^A-Za-z0-9._-]", "_") + ".ogg";
   }

   /** The cards, mastered words first, then the ones being learned, each sorted by word. */
   static List<Note> notes(PhraseBook book, Progress progress) {
      List<Note> out = new ArrayList<>();
      Map<String, Integer> heard = progress.heardForQuiz();
      List<Map.Entry<String, Progress.Card>> cards = new ArrayList<>(progress.cards().entrySet());
      cards.sort(Comparator.<Map.Entry<String, Progress.Card>, Boolean>comparing(e -> !e.getValue().mastered).thenComparing(Map.Entry::getKey));

      for (Map.Entry<String, Progress.Card> e : cards) {
         String key = e.getKey();
         Optional<Phrase> word = book.fixed("hold." + key).or(() -> book.fixed("word." + key)).or(() -> book.fixed(key));
         if (word.isPresent()) {
            Phrase w = word.get();
            // An example: the sentence about this word heard most often.
            Phrase example = book.all()
               .stream()
               .filter(p -> key.equals(Progress.cardKey(p)) && !PhraseBook.isBareWord(p) && !PhraseBook.isFragment(p) && !p.id().equals(w.id()))
               .max(Comparator.<Phrase>comparingInt(p -> heard.getOrDefault(p.id(), 0)).thenComparing(Phrase::id, Comparator.reverseOrder()))
               .orElse(null);
            List<String> sounds = new ArrayList<>();
            sounds.add(w.id());
            StringBuilder back = new StringBuilder()
               .append(div("font-size:1.4em;color:#5b8def", w.reading()))
               .append(div("", en(w)));
            if (example != null) {
               sounds.add(example.id());
               back.append("<hr>")
                  .append(div("font-size:1.6em", example.text()))
                  .append(div("color:#5b8def", example.reading()))
                  .append(div("", en(example)))
                  .append("[sound:").append(soundFile(example.id())).append("]");
            }

            String front = div("font-size:3em", w.bare()) + "[sound:" + soundFile(w.id()) + "]";
            String tags = "learn-or-die chinese " + (e.getValue().mastered ? "mastered" : "learning");
            out.add(new Note("lod-zh-" + key, front, back.toString(), tags, sounds));
         }
      }

      return out;
   }

   private static String en(Phrase p) {
      return p.en().equals(p.text()) ? "" : p.en();
   }

   private static String div(String style, String text) {
      String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
      return style.isEmpty() ? "<div>" + safe + "</div>" : "<div style=\"" + style + "\">" + safe + "</div>";
   }

   /** The text file Anki imports: a header that sets the note type and deck, then one card per line. */
   static String text(List<Note> notes) {
      StringBuilder out = new StringBuilder()
         .append("#separator:tab\n")
         .append("#html:true\n")
         .append("#notetype:Basic\n")
         .append("#deck:Learn or Die::Chinese\n")
         .append("#guid column:1\n")
         .append("#tags column:4\n");

      for (Note n : notes) {
         out.append(clean(n.guid())).append('\t').append(clean(n.front())).append('\t').append(clean(n.back())).append('\t').append(clean(n.tags())).append('\n');
      }

      return out.toString();
   }

   private static String clean(String field) {
      return field.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
   }

   /** Writes the cards and recordings to .minecraft/learn-or-die/anki, and the recordings into Anki itself when found. */
   static Result write(Minecraft mc, PhraseBook book, Progress progress, String voice) throws IOException {
      Path folder = ZhNarratorClient.gameDir().resolve("learn-or-die").resolve("anki");
      Path media = folder.resolve("media");
      Files.createDirectories(media);
      List<Note> notes = notes(book, progress);
      Files.writeString(folder.resolve(FILE), text(notes), StandardCharsets.UTF_8);
      Set<String> ids = new LinkedHashSet<>();
      notes.forEach(n -> ids.addAll(n.sounds()));
      int copied = 0;

      for (String id : ids) {
         Identifier sound = Identifier.fromNamespaceAndPath(book.namespace(), "sounds/" + voice + "/normal/" + id + ".ogg");
         Optional<Resource> resource = mc.getResourceManager().getResource(sound);
         if (resource.isPresent()) {
            try (InputStream in = resource.get().open()) {
               Files.copy(in, media.resolve(soundFile(id)), StandardCopyOption.REPLACE_EXISTING);
               copied++;
            }
         }
      }

      List<Path> profiles = ankiProfiles();
      Optional<Path> into = Optional.empty();
      if (profiles.size() == 1) {
         Path target = profiles.getFirst().resolve("collection.media");

         try (var files = Files.list(media)) {
            for (Path f : files.toList()) {
               Files.copy(f, target.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
         }

         into = Optional.of(target);
      }

      Files.writeString(folder.resolve("README.txt"), readme(into.isPresent()), StandardCharsets.UTF_8);
      return new Result(folder, notes.size(), copied, into, profiles.size());
   }

   /** Anki profiles on this computer: folders with a collection.media folder in Anki's data folder. */
   static List<Path> ankiProfiles() {
      List<Path> out = new ArrayList<>();
      String home = System.getProperty("user.home", "");
      String appData = System.getenv("APPDATA");
      String xdg = System.getenv("XDG_DATA_HOME");
      List<Path> bases = new ArrayList<>();
      if (appData != null) {
         bases.add(Path.of(appData, "Anki2"));
      }

      bases.add(Path.of(home, "Library", "Application Support", "Anki2"));
      bases.add(xdg != null ? Path.of(xdg, "Anki2") : Path.of(home, ".local", "share", "Anki2"));

      for (Path base : bases) {
         if (Files.isDirectory(base)) {
            try (var dirs = Files.list(base)) {
               dirs.filter(d -> Files.isDirectory(d.resolve("collection.media"))).sorted().forEach(out::add);
            } catch (IOException var8) {
               ZhNarratorClient.LOGGER.warn("Could not look into {}: {}", base, var8.toString());
            }
         }
      }

      return out;
   }

   private static String readme(boolean mediaCopied) {
      return "Learn or Die: Anki-Export\n\n"
         + "1. "
         + (mediaCopied
            ? "Die Aufnahmen sind schon in Anki kopiert (collection.media)."
            : "Kopiere alle Dateien aus dem Ordner \"media\" in den Ordner collection.media deines Anki-Profils:\n"
               + "   Windows: %APPDATA%\\Anki2\\<Profil>\\collection.media\n"
               + "   Mac:     ~/Library/Application Support/Anki2/<Profil>/collection.media\n"
               + "   Linux:   ~/.local/share/Anki2/<Profil>/collection.media")
         + "\n2. In Anki: Datei -> Importieren -> "
         + FILE
         + " waehlen und importieren.\n"
         + "   Die Karten landen im Stapel \"Learn or Die::Chinese\".\n\n"
         + "Erneut exportieren und importieren aktualisiert die vorhandenen Karten, statt doppelte anzulegen.\n";
   }

   /** Runs the export and reports in the chat. */
   static void exportNow(Minecraft mc) {
      PhraseBook book = ZhNarratorClient.phrases;
      if (book == null || mc.player == null) {
         return;
      }

      try {
         Result r = write(mc, book, ZhNarratorClient.progress, ZhNarratorClient.speaker == null ? book.voices().getFirst() : ZhNarratorClient.speaker.voice());
         Component folder = Component.literal(r.folder().toString())
            .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.OpenFile(r.folder().toString())));
         mc.player.sendSystemMessage(Component.literal("§6Anki: " + r.notes() + " cards, " + r.sounds() + " recordings → ").append(folder));
         if (r.ankiMedia().isPresent()) {
            mc.player.sendSystemMessage(Component.literal("§7Recordings copied into Anki. In Anki: File → Import → " + FILE));
         } else {
            mc.player.sendSystemMessage(
               Component.literal(
                  r.ankiProfiles() > 1
                     ? "§7Several Anki profiles found: copy the media folder into your profile's collection.media, then File → Import → " + FILE
                     : "§7Copy the media folder into Anki's collection.media (see README.txt), then File → Import → " + FILE
               )
            );
         }
      } catch (IOException | RuntimeException var4) {
         ZhNarratorClient.LOGGER.warn("Anki export failed", var4);
         mc.player.sendSystemMessage(Component.literal("§cAnki export failed: " + var4.getMessage()));
      }
   }
}
