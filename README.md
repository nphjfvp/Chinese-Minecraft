# Chinese Minecraft: meine Version von „Learn or Die: Chinese“

Diese Mod bringt dir beim Minecraft-Spielen Chinesisch bei. Sie ist eine bearbeitbare Kopie von
**[Learn or Die: Language Teacher](https://github.com/Larenlearn/learn-or-die)** von Laren (Version 0.3.5,
nur das chinesische Sprachpaket), ausgerichtet auf **Minecraft 26.3 mit NeoForge**.

Die Lehrerin sagt auf Chinesisch, was du gerade tust (我在挖泥土 *wǒ zài wā ní tǔ*, „Ich grabe die Erde“), und fragt
dich danach ab. Richtige Antworten bringen Buffs, falsche Debuffs.

## Was diese Version anders macht

**Eine Quiz-Runde hat bis zu 6 Fragen zu einem Satz**, zum Beispiel 我在挖泥土 („I am digging the dirt“):

1. **Hören:** Die Lehrerin sagt den Satz nur, ohne Text und ohne Englisch. Welcher war es? Die falschen Antworten
   handeln vom selben Wort (我捡到泥土, 我在放泥土 …), du musst also das Verb heraushören.
2. **Satzanfang:** Welcher Anfang passt? 我在挖… / 我捡到… / 我在放… / 我扔了…
3. **Wort:** Was heißt *dirt*? 泥土 und drei andere Wörter. Bei Sätzen ohne Gegenstand (laufen, springen) wird das
   Verb gefragt: 我在 … *走路*.
4. **Ton:** Welchen Ton hat 泥 ni? nī / ní / nǐ / nì. Gefragt wird eine Silbe des Wortes mit Ton.
5. **Ganzer Satz:** wie bisher.
6. **Sprechen:** den ganzen Satz sagen. Nur wenn das Hilfsprogramm für Sprachantworten läuft und der Quizmodus nicht
   *choice* ist.

Fehlt einem Satz ein Teil (etwa „下雨了“, der mit dem Verb beginnt), fällt die Frage weg. **Jede Frage gibt einen
Effekt**: positiv bei richtig, negativ bei falsch. Hör- und Tonfrage lassen sich unter *Advanced settings* abschalten
(*Listening question*, *Tone question*), falls dir die Runden zu lang sind.

**Töne.** Wörter, bei denen du Töne falsch wählst oder falsch sprichst (der Sprachtest erkennt Tonfehler), kommen
unter den aktiven Wörtern häufiger dran. Bei beherrschten Wörtern rücken die Wiederholungen nicht weiter auseinander,
solange die Töne noch wackeln. In der Lernübersicht sind solche Wörter mit **♪** markiert.

**Pinyin wird weniger.** Bei beherrschten Wörtern zeigt der Satz unten nur noch Schriftzeichen; mit gedrückter rechter
Shift-Taste siehst du das Pinyin trotzdem. Im Quiz stehen bei beherrschten Wörtern keine Pinyin-Hilfen unter den
Antworten. Abschaltbar unter *No pinyin for mastered words*.

**Lernübersicht (Taste J).** Drei Spalten: die Wörter, die du gerade lernst, mit ihrer Serie (●○ = 1 von 2 guten
Runden), die beherrschten Wörter mit der nächsten Wiederholung („due“ = fällig) und der Inhalt des Pfands mit Bildern.

**Fullbright (Taste H).** Alles so hell wie mit Nachtsicht, nur auf deinem Rechner und ohne Effekt. Auch unter
*Advanced settings* → *Fullbright*.

**Gegenstände zurückverdienen (Pfand).** Leert die wütende Lehrerin dein Inventar oder stirbst du, kommen die
Gegenstände ins Pfand der Welt, statt verloren zu gehen oder auf dem Boden zu liegen. **Jede richtige Frage gibt einen
zufälligen Stapel zurück**; was nicht ins Inventar passt, landet vor deinen Füßen. Im Hardcore-Modus bleibt der Tod
das Ende. Beim Tod lässt sich das Pfand unter *Advanced settings* → *Death: items to the pledge* abschalten.

Wie viele Stapel im Pfand liegen, steht oben links neben „learned“ und den Toden (Truhe), was genau drin ist, in der
Lernübersicht (Taste J).

**Die wütende Lehrerin** wird nach **2 verpatzten Runden hintereinander** wütend und nimmt das Inventar ins Pfand.
Verpatzt heißt: Satzanfang, Wort oder ganzer Satz war falsch. Hören, Ton und Sprechen geben nur ihren Effekt.

**Das Quiz lernt nur wenige Wörter auf einmal.** Am Anfang hörst du sehr viel (laufen, schwimmen, Erde, Holz,
Spitzhacke …). Das Quiz fragt aber nur eine feste Zahl von Wörtern ab, standardmäßig 5.

- Ein Wort ist ein Gegenstand, Block oder Mob (Erde, Eichenbretter, Zombie …) oder eine Tätigkeit ohne Gegenstand
  (laufen, sprinten, springen). Zu „Erde“ kann jeder gehörte Satz mit Erde abgefragt werden.
- Neue Wörter sind die, die du im Spiel am häufigsten gehört hast. Im Chat steht dann „★ New word to learn: …“.
- Nach **2 Runden hintereinander, in denen Satzanfang, Wort und ganzer Satz richtig waren,** ist ein Wort beherrscht („New word
  learned“), und das nächste Wort rückt nach. Eine verpatzte Runde setzt die Serie zurück. Hören, Ton und Sprechen
  zählen hier nicht mit; das Sprechen schon deshalb nicht, weil die Spracherkennung danebenliegen kann.
- Beherrschte Wörter kommen mit **Spaced Repetition** wieder: nach 10 Minuten, 30 Minuten, 2 Stunden, 6 Stunden,
  1 Tag, 3 Tagen und dann 1 Woche **Spielzeit**. Eine fällige Wiederholung kommt vor den neuen Wörtern dran. Wer sie
  falsch beantwortet, fängt wieder bei 10 Minuten an.
- Beim Tod bleibt der Lernstand erhalten (im Original wurde er gelöscht).
- Der Zähler „learned“ oben links zählt die beherrschten Wörter.

**Die Lehrerin sagt weiter alles, aber in Ruhe.**

- Sie sagt auch beherrschte Wörter (im Original verstummte sie dabei).
- Sätze über ein Wort, das du gerade lernst, haben unten ein **★** davor.
- Höchstens **ein Satz alle 10 Sekunden.** Was in der Pause passiert, wird nicht nachgeholt. Nach der Pause sagt sie,
  was du in diesem Moment tust; bei mehreren Ereignissen das, das du am seltensten gehört hast.
- Antworten im Quiz (richtig, falsch, wütend) kommen sofort, ohne Pause.

**Mikrofon auswählen.** Unter *Microphone* in den Einstellungen stehen alle Mikrofone, die das Hilfsprogramm findet
(es muss dafür laufen), sonst nimmt es das Standard-Mikrofon des Systems. Im Chat steht, welches Mikrofon aufnimmt.
Wurde nichts gehört, steht dort auch, wie laut es am lautesten war; bei 0 % kommt vom Mikrofon nichts an.

**Einstellungen** (Taste I): *Words learned at once* (Wörter gleichzeitig), *Pause between sentences* (Satzpause) und
*Microphone* stehen im Hauptmenü, *Mastered after N good rounds* (gute Runden bis „beherrscht“), *Angry after N
failed rounds*, *Death: items to the pledge*, *Listening question*, *Tone question*, *No pinyin for mastered words*
und *Fullbright* unter *Advanced settings*. Die Tasten (J Übersicht, H Fullbright) lassen sich unter Optionen →
Steuerung → Tastenbelegung ändern. Die alten Einstellungen „Learned after N correct“, „Learned words“, „Learned rest“, „Never narrate learned
words“, „Re-ask after“, „Quiet after correct“ und „Angry after N wrong“ gibt es nicht mehr, weil das neue System sie ersetzt.

## Lizenz

Das Original steht unter der MIT-Lizenz. Kopieren und Verändern ist erlaubt, solange der Lizenzhinweis (`LICENSE`)
erhalten bleibt.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `langpack-chinese/` | Das chinesische Sprachpaket: alle Sätze (`phrases.json`), die Sprachaufnahmen (`sounds/`), Schrift und Bild der Lehrerin. **Hier lernst du am meisten, wenn du etwas änderst.** |
| `mod/` | Der Programmcode der Mod (Java), aus der Original-Mod zurückgewonnen (dekompiliert), mit Gradle-Build und Tests. |
| `tools/build_jar.py` | Baut aus dem Repo die fertige Mod-Datei. |
| `LICENSE` | Die MIT-Lizenz des Originals. |

## Mod-Datei bauen

**Mit GitHub:** Nach jedem Push baut GitHub Actions die Mod automatisch. Im Repo unter *Actions* → letzter Lauf →
*Artifacts* → `learn-or-die-chinese-custom` herunterladen und entpacken.

**Auf dem eigenen Rechner** brauchst du Java 25 und Python 3.10 oder neuer. Zuerst den Code übersetzen und testen,
dann die Mod-Datei bauen:

```bash
cd mod && ./gradlew build && cd ..
```

```bash
python3 tools/build_jar.py
```

Unter Windows heißt der erste Befehl `cd mod && gradlew.bat build && cd ..`.

Die fertige Datei liegt dann in `build/learn-or-die-chinese-custom+mc26.3.jar`. Beim ersten Mal lädt das Skript die
Original-Mod als Grundlage von GitHub herunter. Kaputtes JSON meldet es, bevor es etwas baut.

## Installieren

1. NeoForge für Minecraft 26.3 installieren (wie beim Original).
2. Im `mods`-Ordner die **Original-Datei** `learn-or-die-chinese-…jar` **löschen**, sonst gibt es zwei Mods mit
   derselben ID.
3. `learn-or-die-chinese-custom+mc26.3.jar` in den `mods`-Ordner legen.

In der Mod-Liste heißt sie dann „Learn or Die: Chinese (eigene Version)“. Die Mod-Datei enthält auch noch die
Fabric-Teile des Originals; die Änderungen gelten aber nur für NeoForge.

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
| `QuizManager` | Wann und was abgefragt wird, der Ablauf einer Runde, wann ein Wort als gelernt gilt. |
| `QuizRound` | Baut die Runde: Hören, Satzanfang, Wort, Ton, ganzer Satz, Sprechen, mit den falschen Antworten. |
| `Tones` | Pinyin-Töne erkennen und eine Silbe in alle vier Töne setzen. |
| `LearningScreen` | Die Lernübersicht (Taste J). |
| `mixin/LightmapFullbrightMixin` | Fullbright. |
| `QuizScreen` | Das Abfragefenster mit den vier Antworten. |
| `Consequences` | Belohnungen und Strafen (Effekte, wütende Lehrerin) und das Pfand. |
| `NarratorHud` | Der Satz auf dem Bildschirm mit Pinyin und Englisch. |
| `NarratorConfig` | Alle Einstellungen mit ihren Standardwerten. |
| `PhraseBook`, `LanguagePacks` | Laden `phrases.json`. |
| `Progress` | Lernstand: Lernwörter (`cards`), Spaced-Repetition-Stufen und Spielzeit. |
| `Speaker` | Spielt die Aufnahmen ab und sorgt für die Pause zwischen den Sätzen. |
| `VoiceHelper`, `SpeechClient` | Antworten per Mikrofon (Hilfsprogramm in `src/main/resources/zhnarrator_voicehelper/`). |

Der Build (`mod/build.gradle`) nutzt NeoForge 26.3.0.99 mit ModDevGradle. Die Tests in `mod/src/test/` prüfen die
Quiz-Auswahl, den Aufbau der Runden, Spaced Repetition, das Pfand und die Sprechpausen mit der echten `phrases.json`. `tools/build_jar.py` baut die
übersetzte Datei aus `mod/build/libs/` automatisch in die Mod ein.

## Credits

- Original: [Learn or Die](https://github.com/Larenlearn/learn-or-die) von Laren, MIT-Lizenz (`LICENSE`).
- Schrift: [LXGW WenKai](https://github.com/lxgw/LxgwWenKai), SIL Open Font License 1.1
  (`mod/src/main/resources/licenses/`).
- Die Mod-Datei enthält wie das Original die [Fabric API](https://github.com/FabricMC/fabric) (Apache License 2.0).
