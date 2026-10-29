# Nihongo Quest (日本語クエスト)

A Fabric mod for **Minecraft Java Edition 26.3** that turns your inventory into Japanese practice.

Every **10th time** you pick up the same kind of item, a panel pops up with the item's Japanese
word. You have **30 seconds** to say it into your microphone. Say it right and you hear a level-up
chime (正解！). Run out of time and the items you just got are deleted (時間切れ…), and the chat
shows you the correct word so you learn it for next time.

- Works offline: speech recognition runs on your computer with [Vosk](https://alphacephei.com/vosk/).
  Nothing is sent over the internet (the one exception is a one-time ~48 MB model download).
- ~200 hand-picked everyday words (林檎 ringo, 剣 ken, 丸太 maruta…), color words for colored
  items (赤い羊毛 *akai youmou*), and Minecraft's own Japanese names for everything else.
- Difficulty presets from *beginner* to *expert*, and every setting can be tuned in a config file.

---

## 1. Build the mod jar

You need **JDK 25** to build (Minecraft 26.x requires Java 25).
Get it free from <https://adoptium.net> (Temurin 25).

In this folder, run:

| Windows                | macOS / Linux    |
|------------------------|------------------|
| `gradlew.bat build`    | `./gradlew build` |

The first build downloads Minecraft and the Fabric toolchain, so it takes a few minutes.
The finished mod is **`build/libs/nihongo-quest-1.0.0.jar`**.

**No Java installed?** Upload this folder to a GitHub repository. The included workflow
(`.github/workflows/build.yml`) builds it automatically; download the jar from the *Actions* tab.

## 2. Install

1. Install **Fabric Loader** for Minecraft 26.3 with the installer from <https://fabricmc.net/use/installer/>.
2. Download **Fabric API** for 26.3 from <https://modrinth.com/mod/fabric-api>.
3. Put both `fabric-api-….jar` and `nihongo-quest-1.0.0.jar` into your `.minecraft/mods` folder.
4. Start Minecraft with the Fabric profile.

On first launch the Japanese speech model downloads in the background (you'll see a chat message).
Quizzes start once it's ready, usually within a minute.

**Multiplayer:** the server and every player need the mod. Players without it are never quizzed.

**macOS:** the first time, macOS asks whether the Minecraft launcher / Java may use the microphone.
Click *Allow* (or enable it later in System Settings → Privacy & Security → Microphone).
**Windows:** check Settings → Privacy → Microphone → "Let desktop apps access your microphone".

## 3. Playing

- The panel appears at the top of the screen with the item, its Japanese word, and a timer bar.
- Speak clearly at a normal pace. Saying it inside a sentence also counts ("これはりんごです").
- *聞こえた (heard)* shows what the recognizer understood, so you can adjust your pronunciation.
- Pausing the game pauses the timer.
- Only the copies of the item that triggered the quiz (plus any more you pick up during it) are removed,
  never the rest of your inventory. Worn armor is only touched as a last resort.
- Creative mode is ignored by default.

## 4. Configuration

Files are created on first launch in **`.minecraft/config/nihongoquest/`**:

### `config.json` — how the game behaves

Every option is explained by comments inside the file. The quickest change is the preset:

| `"difficulty"` | Time | Quiz every | Shows | Match needed |
|---|---|---|---|---|
| `"beginner"` | 45 s | 10 pickups | kanji, kana, romaji, English | 60% |
| `"normal"` *(default)* | 30 s | 10 pickups | kanji, kana, romaji, English | 75% |
| `"advanced"` | 20 s | 5 pickups | kanji, kana, English (no romaji) | 90% |
| `"expert"` | 15 s | 3 pickups | English only — recall the word yourself | 95% |

Advanced users can override any single preset value by removing the `//` in front of it, e.g.
keep *beginner* but answer within 20 seconds:

```jsonc
"difficulty": "beginner",
"timeLimitSeconds": 20,
```

Other options include: quiz on the first-ever pickup, count every item vs. every pickup, items that
never trigger a quiz, only using hand-written words, color words on/off, choosing a specific
microphone, and whether a broken microphone should count as a fail.
Changes apply the next time you load or join a world.

### `vocabulary.json` — the words

Edit any word, or add new ones (including items from other mods). Each entry has `kanji`
(optional), `kana`, `romaji` (optional, generated if missing), and `accept` for alternative
spellings that also count. Wildcards like `"*_log"` cover every wood type at once.

### Your progress

Per-player pickup counts and scores are saved with the world in `<world>/nihongoquest/<uuid>.json`.

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| Chat says the speech model is missing | Download `vosk-model-small-ja-0.22.zip` from <https://alphacephei.com/vosk/models> and unzip it into `config/nihongoquest/` (so you get `config/nihongoquest/vosk-model-small-ja-0.22/`). |
| "Could not open the microphone" | Check OS microphone permission (above). To pick a specific mic, put part of its name in `"microphone"`. All detected mics are listed in `logs/latest.log` (search for "Microphones found"). |
| It never accepts my answer | Watch the *聞こえた* line. Try the `beginner` preset or lower `"matchStrictness"`. Short one-syllable words (矢 *ya*) are hardest; add an easier spelling in `accept`. |
| Quizzes are too frequent / too rare | Change `"quizEveryNPickups"` or `"countMode"`. |

## 6. For developers

- Fabric Loom 1.18, Mojang official names (Minecraft 26.x is unobfuscated), Java 25.
- Versions live in `gradle.properties`. If a newer Fabric API/Loader is out, update them from <https://fabricmc.net/develop>.
- `src/main` — runs on both sides: config, vocabulary, pickup detection, timer, item removal, networking.
- `src/client` — microphone capture, Vosk recognition, model download, HUD panel.
- The server is authoritative (it owns the inventory and the timer); the client only reports a correct answer.
- `./gradlew runClient` starts a development copy of the game with the mod loaded.
