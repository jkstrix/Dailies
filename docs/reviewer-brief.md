# Dailies: brief for the outside reviewer

This brief is for someone reviewing Dailies from outside the project (plan item 4.5). It says which parts are
behaviour to judge, which parts are source the owner provides, how the parts meet, and where the encoder's licences
and the codec patents stand. It describes the repository as it is at version 1.0.0.

## What Dailies is

A client-side Minecraft mod (Java 21, Minecraft 1.21.1, NeoForge 21.1) for making videos of play. It records what the
game is shown, plays it back with no server, has a keyframe editor, and exports video. It also logs moments while
playing, frames shots by itself (the Smart Camera), and has a local MCP server so an AI agent can drive it. It is
MIT licensed (`LICENSE`).

## Behaviour: what to judge against

- **The specification**, `design/dailies-spec.html`, is the authority on behaviour. Every requirement has an id
  (`REC-19`, `EXP-8`). Chapter I says how to read it. Chapter XIV says how it is proved.
- **The tech note**, `design/create-and-aeronautics.html`, goes with chapter VII: how Create contraptions and Sable
  craft were made to record and play back, fault by fault. It explains; the specification decides.
- **The core**, `src/main/java/comedymonkey/dailies` (about 270 classes): recording, the file format, playback, the
  editor, export, the interface, settings, compatibility with Create, Sable, Aeronautics, Flywheel, Iris, Sodium and
  Distant Horizons, and 81 of the 101 MCP tools. It implements chapters II to VII, XIII and XVI. This is the part
  built against the specification, and the part a review of "does it do what the spec says" is mostly about.
- **The tests:** unit tests in `src/test/`, and the scripted run in `src/selftest/` (a mod of its own,
  `dailies_selftest`, never in the Dailies jar). `SelfTest.plan()` lists every step and its group. Chapter XIV lists
  what the run shows, and, under "Needs two players, a server, a shader pack or a person", what it does not.

## Provided source: the owner's own code

These are written by the owner and used as they stand. The specification describes them (chapters VIII to XII and
appendices A and B), so they are judged against it too, but the owner decides on changes to them. The Smart Camera in
particular is the owner's alone: others correct only ids, links and counts.

| Where | What |
|---|---|
| `reference/src/main/java/comedymonkey/dailies/` | 79 classes, about 17,500 lines, compiled into the same mod (`build.gradle` adds the folder as a source directory). |
| `reference/.../moments/` | Moments: sessions, the event tracker, who was together, voice segments, laughter and shouting (with the bundled YAMNet classifier), optional transcription and summaries, scoring, clips, event pictures. |
| `reference/.../smartcamera/` | The Smart Camera: its four modes, the director, and the solvers. |
| `reference/.../mcp/` | The MCP server (HTTP, JSON-RPC, remote access over HTTPS with a token), and the 20 tools in its Moments group. |
| `reference/src/main/resources/assets/dailies/web/moments.html` | The moments page, served by the MCP server at `GET /`. |
| `reference/.../compat/` | Dailies' side of the companion and of McpCraft. |
| `companion/` | The Dailies Companion, a separate server mod (MIT, seven classes, its own Gradle build and README). It tells players' games about things only a server knows. |

## How the parts meet

- **One interface: `comedymonkey.dailies.Host`** (`reference/src/main/java/comedymonkey/dailies/Host.java`). The
  provided code reaches the rest of the mod only through it. It holds two interfaces:
  - `Host.Mod`, answered by the core's `core/CoreHost`: the data folder, settings, the recording being made, the
    recording open in playback, seeking and opening, where a moment is found (`locate`, `locateClip`), scenes, export
    presets, the export queue (`queueExport`), adding a Smart Camera keyframe, and registering the core's tools.
  - `Host.Crafts`, answered by the core's `compat/sable/SableCompat` when Sable is installed: where craft are and
    how they move, for the camera and for Moments. The defaults suit a game without Sable.
- **The other way**, the core calls the provided entry points: `Moments.init()`, `McpCraftCompat.init()`,
  `ServerMoments.init()` and `McpHttpServer.applyConfig()`; it feeds in voice (`Moments.onVoiceChatAudio`) and
  markers; it gives the Smart Camera a keyframe kind and calls its solver; it lists the provided mixins. Chapter XV of
  the specification lists each step and where the core does it.
- **The companion** talks to the player's game over an optional NeoForge channel, `dailies_companion:event`
  (chapter XII, SRV-1 to SRV-5). Neither side needs the other installed.

## The encoder and its licences

Dailies runs FFmpeg's `ffmpeg` and `ffprobe` as separate programs. It does not link against them. They are packed
inside the jar and unpacked on first export to `<game>/dailies/encoder/<platform>/`, where any FFmpeg build can be
put in their place, as the LGPL asks.

**What the jar bundles.** The project's own FFmpeg 8.1.2 build: `tools/ffmpeg/build.sh`, with pinned sources and
checksums in `tools/ffmpeg/sources.lock`, built, checked and packed for every platform by the workflow in
`.github/workflows/ffmpeg.yml`, for Windows x64, Linux x64 and arm64, and macOS arm64 and x64
(`gradle.properties`: `dailies.encoderSource=dailies`).

- It is LGPL 2.1 or later: no `--enable-gpl`, `--enable-nonfree` or `--enable-version3`, and no x264, x265 or
  OpenH264. Its `verify` step refuses a build that has any of them.
- It has every vendor's hardware encoders: NVIDIA NVENC, AMD AMF, Intel Quick Sync (oneVPL), Media Foundation,
  VA-API on Linux, VideoToolbox on macOS. In software: SVT-AV1, libaom, libvpx, Opus, and FFmpeg's own AAC, ProRes
  and DNxHR. AMF's screen-capture filter is left out (its header in AMF 1.4.36 is C++ only); Dailies does not capture.
- Each platform's exact configuration is in `META-INF/BUILDINFO-<platform>.txt` inside its encoder jar.
  `THIRD-PARTY-NOTICES.md` names each library, its version and its licence; the build's own licence texts and notices
  go into the jar under `licenses/ffmpeg-build`. On Windows the MinGW GCC runtime and winpthreads are linked in
  statically; their texts are `licenses/gcc-COPYING.RUNTIME` and `licenses/winpthreads-COPYING`.
- `-Pdailies.encoderSource=bytedeco` still packs the JavaCPP Presets' Windows-only build (`org.bytedeco:ffmpeg`,
  LGPL 3, which contains OpenH264, never used); its licence texts are kept apart in `src/encoder-licenses/bytedeco`
  and go into the jar only then.

## The codec patent position

- **H.264 and H.265** come from hardware encoders only: the graphics driver's or the operating system's (NVIDIA,
  AMD, Intel, Apple, Windows Media Foundation). Dailies carries only the open headers needed to call them. It carries
  no software encoder for either: x264 and x265 are GPL and would make the whole build GPL.
- **OpenH264** is not in the bundled build. Cisco's patent licence for OpenH264 covers only binaries fetched from
  Cisco, so the project's build leaves it out, and Dailies refuses it by name anyway (`export/Codecs.java`,
  `FORBIDDEN`). Only the optional JavaCPP Presets build (`-Pdailies.encoderSource=bytedeco`) contains it.
- **AV1 and VP9** are offered in software (SVT-AV1, libaom, libvpx) under royalty-free patent grants: the Alliance
  for Open Media Patent License 1.0 and Google's additional grant for libvpx. Those texts are in the licences folder.
- **ProRes and DNxHR** (editing masters) and **AAC** (sound) are FFmpeg's own encoders. Their patent position has
  not been assessed here.
- A machine without a hardware H.264 encoder is offered AV1, VP9 or an editing master, and told why H.264 is missing.

The decisions behind this are in chapter XIV of the specification.

## Where to start

1. `README.md`, then chapter I of the specification.
2. The specification end to end, then the tech note.
3. `python tools/ci/check_spec.py` checks the two design documents: requirement ids, links and well-formed HTML.
