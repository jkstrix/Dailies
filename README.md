# Dailies

Dailies is a Minecraft mod (NeoForge 1.21.1, client side) for making videos of play. It records what your game is
shown to its own file, plays that back later with no server, lets you fly a camera through it and edit on a keyframe
timeline, and exports finished video. It also keeps a log of what happened while you played ("moments"), frames shots
by itself (the Smart Camera), and can be driven by an AI agent over a local MCP server.

It is written to work with Create, Sable physics craft and Create Aeronautics, with Sodium and Iris shader packs, and
with Simple Voice Chat.

## Reading order

1. This file.
2. `design/dailies-spec.html`, chapter I ("Ground rules"): what is being built, and the principles.
3. The rest of `design/dailies-spec.html`: recording, the file, playback, the editor, export, Create and flying
   machines, the Smart Camera, moments, the MCP server, the moments page, the server side, settings, the tests, what
   is provided, and the appendices (every MCP tool; the stored records).
4. `design/create-and-aeronautics.html`: a technical note on how Sable and Create keep their machines, and what that
   means for recording and playing them back.
5. `docs/manual/dailies-manual.html`: the manual for someone using the mod, with pictures taken from the running game.

## What each folder holds

| Folder | What it is |
|---|---|
| `design/` | The specification and the Create and Aeronautics note. The specification is the authority on behaviour. |
| `src/` | The core of the mod: recording, the file format, playback, the editor, export, the interface, compatibility; and in `src/selftest/` the scripted test, a mod of its own (`dailies_selftest`) that never goes into the Dailies jar. Implements the specification in `design/`. |
| `reference/` | The owner's own code, compiled into the same mod: Moments, the Smart Camera, the MCP server and its tools, the moments page. It meets the core through one interface, `comedymonkey.dailies.Host`. |
| `companion/` | The Dailies Companion: a small separate server mod that tells players' games about things only a server knows. Its own Gradle build and README. |
| `docs/manual/` | The user manual and its pictures. |
| `docs/` | Also the internal code review of 18 November 2026, and the brief for an outside reviewer (`reviewer-brief.md`). |
| `tools/` | `tools/ffmpeg/`: our own encoder build, the one the jar packs. `tools/ci/`: the scripted run for CI, and `check_spec.py`, which checks the design documents. |
| `run-test/`, `run-modded/` | Game folders the scripted tests run in. Not kept in git. |

## Where the work comes from

Dailies is its own mod. It is inspired by the owner's earlier work on recording play and cutting it into video. The
specification in `design/` is the authority on what this mod does.

- That earlier work, this repository, and other replay mods may all be read while working on it.
- Everyday sources are the documents in `design/`, the code in this folder, Minecraft and NeoForge, and the public
  APIs of the mods Dailies works beside (Create, Sable, Create Aeronautics, Simple Voice Chat, Distant Horizons).
- Where a mod has to be reached that offers no API (Sable, Create), it is done by reflection on its public classes,
  failing soft with one line in the log when a version differs.

Chapter I of the specification says the same, with what is being built.

## Building

Java 21. The Gradle wrapper fetches everything else.

| To do this | Run |
|---|---|
| Build the mod jar (`build/libs/`) | `gradlew build` |
| Unit tests | `gradlew test` |
| The timings, among them the compression benchmark on the long fixtures (`build/reports/zstd-benchmark.md`) | `gradlew benchmarks` |
| Train the Zstandard dictionaries recordings are packed with again, on the long fixtures, into `src/main/resources/assets/dailies/zstd` | `gradlew trainDictionaries` |
| Start the game with the mod, for development | `gradlew runClient` |
| The scripted test in a plain game (about 2 minutes) | `gradlew runTestClient` |
| The scripted test with the built jar beside Create, Sable, Aeronautics, Sodium, Iris, Simple Voice Chat and the companion (about 3 minutes) | `gradlew runTestModded` |
| Only some groups of the scripted test, with the groups they need | `gradlew runTestModded -PdailiesOnly=trains,library` |
| Only the groups that failed in the last scripted test in that game folder | `gradlew runTestModded -PdailiesOnly=failed` |
| Sixty seconds at the controls while recording, then the recording open in the editor | `gradlew runHandsOn` |
| Retake the manual's pictures | `gradlew runManualShots` |
| Build the companion (`companion/build/libs/`) | `gradlew build` in `companion/` |

The scripted test drives the real game through its own MCP tools and prints `DAILIES TEST PASSED`, or
`DAILIES TEST FAILED:` and the reason, with the number of checks skipped. Steps that could not run (a mod not
installed, something that needs a second player) are listed as `DAILIES TEST skipped:`. Something that is there and
does not work is a failure, not a skip. `runTestModded` also fails at once if a mod it expects is not loaded
(`-Pdailies.testExpectMods` changes the list).

The groups are `settings`, `world`, `watching`, `moments`, `recording`, `packs`, `playback`, `balloon`, `export`,
`editor`, `renderer`, `library`, `library-screens`, `points-of-view`, `addon`, `trains`, `edges`, `bestiary`, `tour`,
`camera-films` and `budgets`. A group that works on another's recording brings it in: `library` also runs `recording`
and `packs`. Asked for by name, the long groups (`bestiary`, `tour`, `camera-films`) run without their flags.

The steps that stage things in a world of their own and compare playback with the live game (the addon, trains, edges,
the bestiary and the tour) keep their recordings as fixtures in `dailies/fixtures` and play them back on later runs.
They are recorded again only when the code that writes recordings changes, their staging changes, or
`-PdailiesFlags=dailies.test.recordTours` asks for it.

The scripted test is its own jar, `build/libs/dailies-selftest-*.jar`, built by `gradlew selftestJar`. The dev runs
(`runTestClient`, `runHandsOn`, `runKitShots`) load it from its classes; `runTestModded` and `runManualShots` get the
jar in `run-modded/mods` from `installTestMods`. `runClient` and `runPlayModded` never have it.

`runTestModded` copies the other mods from a folder of jars that you name, in your own `~/.gradle/gradle.properties`:
`dailies.modsFolder=<folder>` (or pass `-Pdailies.modsFolder=<folder>`). Jars that should replace one of those, such as
a newer build of a mod, go in `dailies.modJars=<jar>,<jar>`. The modded runs stop with a message when the folder is not
set. The companion jar is taken from `companion/build/libs`, so build the
companion first.

Video is encoded by a bundled FFmpeg, our own LGPL build (`tools/ffmpeg/build.sh`), for Windows, Linux and macOS,
with every vendor's hardware encoders and without x264, x265 or OpenH264. Its jars are not kept in git: build them with
the "FFmpeg for Dailies" workflow (or `tools/ffmpeg/build.sh build <platform>` and `pack`) and put the
`dailies-ffmpeg-jars` download in `tools/ffmpeg/out/jars` before `gradlew build`. `-Pdailies.encoderSource=bytedeco`
packs the JavaCPP Presets' Windows-only build instead. `THIRD-PARTY-NOTICES.md` lists what is bundled and under which
licences.

## Licence

MIT, see `LICENSE`. The bundled encoder is LGPL and is run as a separate program.

## Who to ask

James Knight, the owner of the project and the author of the specification and of everything in
`reference/` and `companion/`.
