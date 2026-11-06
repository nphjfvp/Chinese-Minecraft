# Chinese Minecraft: meine Version von „Learn or Die: Chinese“

Diese Mod bringt dir beim Minecraft-Spielen Chinesisch bei. Sie ist eine bearbeitbare Kopie von
**[Learn or Die: Language Teacher](https://github.com/Larenlearn/learn-or-die)** von Laren (Version 0.3.5,
nur das chinesische Sprachpaket), ausgerichtet auf **Minecraft 26.3 mit NeoForge**.

Die Lehrerin sagt auf Chinesisch, was du gerade tust (我在挖泥土 *wǒ zài wā ní tǔ*, „Ich grabe die Erde“), und fragt
dich danach ab. Richtige Antworten bringen Buffs, falsche Debuffs.

Das Original steht unter der MIT-Lizenz. Kopieren und Verändern ist erlaubt, solange der Lizenzhinweis (`LICENSE`)
erhalten bleibt.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `langpack-chinese/` | Das chinesische Sprachpaket: alle Sätze (`phrases.json`), die Sprachaufnahmen (`sounds/`), Schrift und Bild der Lehrerin. **Hier lernst du am meisten, wenn du etwas änderst.** |
| `mod/` | Der Programmcode der Mod (Java), aus der Original-Mod zurückgewonnen (dekompiliert). |
| `tools/build_jar.py` | Baut aus dem Repo die fertige Mod-Datei. |
| `LICENSE` | Die MIT-Lizenz des Originals. |

## Mod-Datei bauen

**Mit GitHub:** Nach jedem Push baut GitHub Actions die Mod automatisch. Im Repo unter *Actions* → letzter Lauf →
*Artifacts* → `learn-or-die-chinese-custom` herunterladen und entpacken.

**Auf dem eigenen Rechner** (Python 3.10 oder neuer):

```bash
python3 tools/build_jar.py
```

Die fertige Datei liegt dann in `build/learn-or-die-chinese-custom+mc26.3.jar`. Beim ersten Mal lädt das Skript die
Original-Mod als Grundlage von GitHub herunter. Kaputtes JSON meldet es, bevor es etwas baut.

## Installieren

1. NeoForge für Minecraft 26.3 installieren (wie beim Original).
2. Im `mods`-Ordner die **Original-Datei** `learn-or-die-chinese-…jar` **löschen**, sonst gibt es zwei Mods mit
   derselben ID.
3. `learn-or-die-chinese-custom+mc26.3.jar` in den `mods`-Ordner legen.

In der Mod-Liste heißt sie dann „Learn or Die: Chinese (eigene Version)“.

Die Einstellungen (Taste I im Spiel) liegen in `.minecraft/config/zhnarrator.json`, dein Lernstand in
`.minecraft/config/zhnarrator-progress.json`. Beide bleiben beim Wechsel auf diese Version erhalten.

## Sätze bearbeiten (`langpack-chinese/assets/zhnarrator_zh/phrases.json`)

Die Datei hat 3.151 Sätze und 515 Wörter. Ein Satz sieht so aus:

```json
{
  "id": "walk",
  "action": "walk",
  "key": null,
  "text": "我在走路。",
  "reading": "wǒ zài zǒu lù",
  "en": "I am walking.",
  "parts": [
    {"text": "我",   "reading": "wǒ",     "role": "pron"},
    {"text": "在",   "reading": "zài",    "role": "part"},
    {"text": "走路", "reading": "zǒu lù", "role": "verb"}
  ]
}
```

- `text`: der chinesische Satz, `reading`: Pinyin, `en`: die englische Übersetzung, die unter dem Satz steht.
- `parts`: der Satz in Wörtern. `role` bestimmt die Farbe auf dem Bildschirm (Pronomen, Verb, Objekt …).
- `id` verweist auf die Aufnahme: `sounds/el-gu2ktiu9/normal/<id>.ogg` und `…/slow/<id>.ogg` (langsam gesprochen),
  eingetragen in `sounds.json`.

Was du ohne neue Aufnahmen gefahrlos ändern kannst: Übersetzungen (`en`), Pinyin (`reading`), die Aufteilung in Wörter
(`parts`). Neue Sätze brauchen dagegen eigene `.ogg`-Aufnahmen (normal und langsam) und einen Eintrag in `sounds.json`.
Außerdem enthält die Schrift (`font/lxgwwenkai-subset.ttf`) nur die Schriftzeichen, die die Mod bisher benutzt. Neue
Zeichen werden deshalb in einer anderen Schrift angezeigt, bis die Schrift erweitert wird.

## Programmcode (`mod/`)

Der Code stammt aus der NeoForge-Datei der Original-Mod und ist mit [Vineflower](https://github.com/Vineflower/vineflower)
dekompiliert. Die Namen sind echt, Kommentare gibt es aber keine. Die wichtigsten Klassen in
`mod/src/main/java/dev/ame/zhnarrator/`:

| Klasse | Aufgabe |
|---|---|
| `ActionDetector` | Erkennt, was du tust (laufen, abbauen, essen, kämpfen …), und wählt den passenden Satz. |
| `QuizManager` | Wann und was abgefragt wird, wann ein Wort als gelernt gilt. |
| `QuizScreen` | Das Abfragefenster mit den vier Antworten. |
| `Consequences` | Belohnungen und Strafen (Effekte, wütende Lehrerin). |
| `Progress` | Speichert, welche Sätze du schon gelernt hast. |
| `NarratorHud` | Der Satz auf dem Bildschirm mit Pinyin und Englisch. |
| `NarratorConfig` | Alle Einstellungen mit ihren Standardwerten. |
| `PhraseBook`, `LanguagePacks` | Laden `phrases.json`. |
| `Speaker` | Spielt die Aufnahmen ab. |
| `VoiceHelper`, `SpeechClient` | Antworten per Mikrofon (Hilfsprogramm in `src/main/resources/zhnarrator_voicehelper/`). |

**Noch offen:** Für den Code gibt es noch keinen Gradle-Build. Solange baut `tools/build_jar.py` die Mod mit dem
unveränderten Original-Code, und nur Änderungen am Sprachpaket landen in der Mod-Datei. Liegt später eine kompilierte
Datei in `mod/build/libs/`, baut das Skript sie automatisch statt des Original-Codes ein.

## Credits

- Original: [Learn or Die](https://github.com/Larenlearn/learn-or-die) von Laren, MIT-Lizenz (`LICENSE`).
- Schrift: [LXGW WenKai](https://github.com/lxgw/LxgwWenKai), SIL Open Font License 1.1
  (`mod/src/main/resources/licenses/`).
- Die Mod-Datei enthält wie das Original die [Fabric API](https://github.com/FabricMC/fabric) (Apache License 2.0).
