# Recording a game for the reliability work

The recorder lets us replay exactly what your crew's devices did, so problems you hit at a game can be reproduced and fixed. It sits between your devices and CRG, passes everything through unchanged, and writes down what each device sent.

## What you need

- The CRG folder you normally run from, on the scoreboard laptop.
- Java 11 or newer. (CRG itself runs on Java 8; the recorder needs 11.)
- From the recorder build: `crg-recorder.jar` and the launcher for your system (`record-game.bat` for Windows, `record-game.command` for macOS, `record-game.sh` for Linux).

## Before the game

1. Copy `crg-recorder.jar` and the launcher into the CRG folder, next to `scoreboard.sh`.
2. **Start CRG with the launcher instead of CRG's own:** double-click `record-game.bat` or `record-game.command` (or run `./record-game.sh`). It starts CRG and the recorder together and prints the addresses for other devices.
3. Connect devices exactly as usual, on port 8000. The start page's links and QR codes already point to the recorder.

Nothing changes for the crew. If CRG's own launcher is already running, close it first: both want port 8000.

## During the game

Leave the window open. If something odd happens, note the time; that makes it much easier to find in the recording.

## After the game

Press **Ctrl-C** in the window (or close it). That stops CRG and the recorder, and writes the recording to `captures/<date and time>/` in the CRG folder:

| File | What it is |
| --- | --- |
| `trace.crgtrace.jsonl` | Every connection, message and disconnection, with times. |
| `seed.json` | The game state when recording started. |
| `files/` | Anything uploaded during the game (logos, game files). |

Zip that folder and send it, with your note of what went wrong and when.

## Privacy

- **Names are hidden by default.** Skater, team, league and official names, pronouns and free-text notes become placeholders such as `Name-7` and `Team-2`, the same placeholder for the same name everywhere, so the game still replays.
- Spreadsheets loaded during the game (statsbooks, rosters) are **not kept**, because names can't be removed from them.
- Session cookies and device addresses are never recorded.
- Hiding names is best-effort. Look through a recording before sharing it beyond the people working on CRG.
- To keep names (for your own league's use only): add `--redact=false` to the line in the launcher.

## If something goes wrong

- **The window shows `CRG exited`:** CRG stopped, so the recorder stopped too. Start the launcher again; CRG restores from its autosave as usual, and a new recording begins.
- **The recorder crashed without writing `trace.crgtrace.jsonl`:** everything up to the crash is in `events.partial.jsonl`. Run `java -jar crg-recorder.jar --assemble=captures/<folder>` to build the trace from it.
- **Port 8000 is already in use:** another CRG is still running. Close it, then start the launcher again.
