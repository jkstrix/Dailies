# Dailies: plan to close the gaps

This plan turns the review of the Dailies folder into work. It covers three kinds of change:

- **Restore:** features the earlier build has that the specification leaves out.
- **Add:** features neither has that make Dailies the best tool there is for turning long multiplayer sessions into videos.
- **Fix:** problems inside the Dailies folder itself.

Every task names who does it:

- **Spec:** new or changed requirements in `design/dailies-spec.html`, for the builder of the core to implement.
- **Yours:** the owner's own code (Moments, Smart Camera, MCP, the page, the companion). Build and test it in the earlier build, where it can run, then carry it into `reference/` again.
- **Folder:** documents and housekeeping in this folder.

Sizes are rough: **S** is under an hour, **M** is a session, **L** is several sessions.

The earlier build is the owner's previous work on the same idea. It may be read and named. This plan stays in the folder.

## Principles

Every task below follows these. Where a task once fell short of one, it has been rewritten.

1. **Nothing is deferred.** No "later" and no "version 2". A large feature is staged into steps, and every step is in this plan.
2. **Recordings are self-contained.** A recording copied to another PC, or sent to a friend, plays exactly as it did, with nothing fetched from elsewhere.
3. **Nothing that can't be recovered is thrown away at record time.** Data that can only be captured live (timing, packs, audio, positions) is captured from the first version, even before a feature uses it.
4. **Every platform, every GPU.** Windows, Linux and macOS, and hardware encoding on NVIDIA, AMD, Intel and Apple, from the first release.
5. **Targets are numbers.** Speed, size and accuracy have stated budgets, and the scripted run measures them.
6. **Nothing destructive is instant.** Deletes go to a bin first; edits make new recordings and leave the sources alone.
7. **AI stays optional and off by default.** Anything that uses a model, local or on a server, sits behind `momentsAiEnabled`. Everything else works without it.
8. **Other players are treated fairly.** Their voices are captured only as the owner's settings allow, and nothing about them leaves the machine unless AI is switched on.

---

## Status at v1.0 (checked against the code, 18 November 2026)

**Done** means the spec row and the code are there, and usually a test too. **Partly** says what is missing. **Can't tell** means the work lives outside this repository (the earlier build, the carry-over script, McpCraft).

| # | Status | What is missing |
|---|---|---|
| 0.1 | Can't tell | The carry-over script is not in this repository |
| 0.2 | Done | Voice capture is `sound/VoiceChatPlugin` and `VoiceCapture`, in the core |
| 0.3 | Done | As `compat/sable/SableCompat`, not `SableCrafts`; it has the four craft methods of 2.13 too |
| 0.4 | Done | The five topics are spec rows: REC-19/PLAY-17, FILE-9, EDIT-8, REC-32, CMP-1 |
| 0.5 | Done | McpCraft (its own repository) now stands down only while a Dailies recording plays back, asked through `DailiesApi.isPlayback` (PLAY-6); its Flashback check is gone, as the owner asked |
| 0.6 | Can't tell | Outside this repository |
| 1.1 | Done | |
| 1.2 | Done | Combine, trim, their screens and tools, and FILE-11 to FILE-14 and FILE-16, all tested |
| 1.3 | Done | A job can be any recording and tick range (a point of view job, seen through its recorder's eyes), as well as a scene of the open recording; `Host.queueExport` has an overload for it |
| 1.4 | Partly | The path preview and its Preview path button (a Smart Camera stretch played at up to 20 times speed without drawing the world) are done. EDIT-10 baking and the solver's fast run are the owner's Smart Camera work |
| 1.5 | Done | |
| 1.6 | Done | |
| 1.7 | Done | |
| 2.1 | Partly | Every event is detected, weighted, merged and now checked by the scripted run, train events included. The suggested cameras for craft and train events are the owner's Smart Camera work |
| 2.2 | Partly | Done apart from "clips made for Shorts suggest a closer chase", the owner's Smart Camera work |
| 2.3 | Done | |
| 2.4 | Done | |
| 2.5 | Done | |
| 2.6 | Done | Linking, switching, and the same-stretch batch, which is queued |
| 2.7 | Not done | Group shots: the owner's Smart Camera work |
| 2.8 | Done | |
| 2.9 | Done | The sound labels run only with `momentsAiEnabled` on (and `momentsBurstLabels`), as principle 7 asks |
| 2.10 | Done | |
| 2.11 | Done | |
| 2.12 | Done | |
| 2.13 | Partly | `Host.Crafts` and any-entity targets are done. Craft targets (`craft:<id or name>`, whole-craft framing, headings, hold-then-follow, the editor list, the balloon test) are the owner's Smart Camera work |
| 2.14 | Done | |
| 3.1 | Done | The own LGPL build is packed for every platform: Windows x64, Linux x64 and arm64, macOS Apple silicon and Intel, built on GitHub by the FFmpeg workflow (`dailies.encoderSource=dailies`). EXP-1, EXP-8 and the notices describe it. Only Windows is game-tested here |
| 3.2 | Done | |
| 3.3 | Done | |
| 3.4 | Done | |
| 3.5 | Done | Embeddium and Oculus are treated as aliases, as the chapter says |
| 3.6 | Not done | Appendix B's interactions example is still illustrative, and has no `group_formed` record |
| 3.7 | Done | The old `moments_companion:*` channels are gone too |
| 3.8 | Done | BUD-1 to BUD-8 are in the spec. BUD-2 is over budget in generated terrain (225 to 354 MB an hour, target 150). The second half of BUD-5, and BUD-8, are not measured |
| 3.9 | Done | Chapter XIV has a step for every requirement the scripted run tests, and lists what nothing tests yet |
| 3.10 | Done | |
| 3.11 | Done | The server's numbering was in every recording all along (the world data's ids, read while joined); playback now applies it as NeoForge does on joining and reverts it after (REC-37, PLAY-25), fixing recordings already made. The registries group simulates a server with every sound numbered 843 on and passed in game |
| 3.12 | Done, not yet confirmed in game | Distant Horizons is given a directory junction to the playback folder on Windows, which it can rename onto itself while the database is still open; unit tested on Windows with a held file. The first fix, spare copies beside a held folder, worked in game but piled up a copy (about 1 GB) per world swap, because Distant Horizons holds the folder after every close; it is replaced, and its spares are deleted |
| 3.13 | Done, not yet confirmed in game | Lootr's "fillUpdateTag on the client side" error is left out of the log while a snapshot is taken; what is recorded is unchanged; unit tested on a real logger |
| 3.14 | Done, not yet confirmed in game | Exports ask Distant Horizons whether the far terrain is in on a thread of their own, and stop waiting after 5 s without an answer |
| 4.1 | Partly | `reference/` compiles with every build; the carry-over script is outside this repository |
| 4.2 | Done | `tools/ci/check_spec.py` checks ids, citations, links and the HTML; it runs first in the scripted-test workflow |
| 4.3 | Can't tell | Needs the earlier build |
| 4.4 | Done | Both documents read end to end; contradictions fixed (encoder, tool counts, settings groups, the companion, the tech note's solved items) |
| 4.5 | Done | `docs/reviewer-brief.md` |
| 4.6 | Not done | The review gate. The internal code review of 18 November is done; this is the outside review 4.5 briefs |
| 4.7 | Done | |

---

## Phase 0: from the earlier build

These use source or behaviour that lives in the earlier build. Do them while that tree is still at hand.

| # | Task | Side | Size |
|---|---|---|---|
| 0.1 | **Keep the carry-over script.** The script that produced `reference/` lives in a temporary folder that a reboot can clear. Move it next to the earlier build's source and commit it there. Every "Yours" task below re-runs it. | Folder | S |
| 0.2 | **Provide the voice capture.** Moments needs 48 kHz mono audio per speaker, but the code that takes it from Simple Voice Chat's API was not carried. Separate the part that listens to Simple Voice Chat and calls `Moments.onVoiceChatAudio` from the part that writes recordings, and carry the first. Add the voice chat API events it uses to chapter XV. | Yours | M |
| 0.3 | **Provide the Sable answers for the camera.** `Host.Crafts` has nothing behind it. The eight camera methods in the earlier build's Sable code are pure reflection with no other dependency. Carry them as `comedymonkey.dailies.compat.sable.SableCrafts implements Host.Crafts`, and add the four craft-target methods of 2.13 while the Sable code is still at hand. The recording and playback parts are written up in the tech note, which may be read alongside that code. | Yours | S |
| 0.4 | **Write down behaviour the spec needs for Phase 1.** For server resource packs, combining recordings, the camera path preview, in-world markers and Distant Horizons, note what each does as a user sees it. The earlier build's code may be read while writing that down. Phase 1 turns those notes into requirements. | Folder | M |
| 0.5 | **Update McpCraft's playback check.** `dev.mcpcraft.core.Replay` recognises the earlier build's playback server by class name. Make it accept both that and Dailies' documented check (PLAY-6), so it works before and after the switch. | Yours | S |
| 0.6 | **Keep the earlier build.** Back up its git history and leave the tree where it can still be read. It is the work Dailies is inspired by. | Folder | S |

---

## Phase 1: restore what the spec dropped

All of these are core features, so all are spec work. Each needs requirement rows, any settings in chapter XIII, any MCP tools in appendix A, and a test step in chapter XIV.

### 1.1 Server resource packs (Spec, M)
- **Problem:** REC-11 discards the resource pack steps, so a recording from a server with its own packs plays back with the wrong textures, models and sounds.
- **REC-19, capture:** for every pack the server pushes and the player accepts, record the push (id, URL, hash, required or not) at its tick, and store the pack's bytes inside the recording. A pack the server removes is recorded as removed at its tick. A pack the player declined is not applied in playback either, so playback matches what the player saw.
- **PLAY-17, playback:** the recorded packs are applied, in their recorded order, from the recorded ticks. A scene look setting, "use the server's resource packs" (default on), turns them off for that scene.
- **Self-contained (principle 2):** packs live in the recording, stored once per recording however often they are pushed. A pack is tens of megabytes against recordings of gigabytes, so the cost is small and the recording never depends on a cache or a server that may be gone.
- REC-11's note gains the exception.

### 1.2 Combining and trimming recordings (Spec + Yours, L)
- **Combine (FILE-9):** join recordings in any order into a new one. The sources are left untouched.
  - Each source keeps its own world: the boundary replays that source's opening snapshot, so recordings from different worlds or dimensions combine cleanly.
  - Sources with different game versions are refused. Different mod lists are allowed, with the differences listed (PLAY-5 applies per span).
  - The boundary is marked on the timeline and adds no dead time.
- **Trim (FILE-10):** cut ranges out of a recording into a new one, such as the hour spent mining. A cut that does not land on a segment boundary gets a fresh snapshot written at the cut, built from playback's own state at that tick. The core's playback therefore needs to be able to describe its world as a snapshot, which the snapshot writer already does for live recording.
- **Screens:** pick recordings, drag to order, preview each boundary, choose ranges to cut on a timeline with the moments of 2.3 shown, so the cut can be made around the good bits.
- **MCP:** `replay_combine` and `replay_trim`.

#### Keeping moments working (FILE-11 to FILE-14, FILE-16)
Moments point at a recording id and tick. Combining or trimming makes a new recording with a new id and new ticks, so without help every clip and cue into it breaks. The fix leaves moments alone and teaches recordings where their footage came from.

**Rejected: rewriting the moments.** One source can feed several edited recordings, but a moment holds only one id and tick. Deleting an edited recording would leave moments pointing at nothing. And the session files are read by the page and the tools, so rewriting them on every edit is fragile.

**FILE-11, the source map.** Every recording made by combining or trimming stores in its header the stretches of original recordings it is made of:

```
spans:
  - source: A   ticks 0–36,000      → here at 0
  - source: A   ticks 48,000–72,000 → here at 36,000    (hour of mining cut out)
  - source: B   ticks 0–20,000      → here at 60,000    (next evening's session)
```

A moment recorded at (A, tick 50,000) falls in the second span, so in this recording it is at 36,000 + (50,000 − 48,000) = tick 38,000. A plain recording has no spans and stands for itself.

Rules:
1. **Spans always point at originals.** Combining or trimming a recording that was itself edited copies its spans across, with the ranges adjusted, so every map is one step deep however many edits there were.
2. **Moments never change.** They keep the original id and tick they were recorded with.
3. **Cut-out moments say so.** A moment in a removed stretch has no position in the new recording. The page shows "cut from this recording" and offers the recordings that still hold it.
4. **Clips survive cuts.** A clip maps to every stretch of it that survives. A clip with a cut inside plays as the edit has it, both pieces back to back, and is flagged `cut` with the length removed. Nothing is dropped.
5. **Combine adds no time.** The boundary between two sources is a marked point on the timeline, not dead ticks.

**FILE-12, the index.** `dailies/recordings/index.json` lists every recording's id, file, size, modified time and spans. It is updated whenever a recording is saved, combined, trimmed, renamed, moved or deleted. On start-up it is checked in the background against each file's size and modified time, and any entry that differs is re-read from that file's header. It is rebuilt from the headers when missing. It answers "which files contain (A, tick 50,000), and at what tick?" without opening every file.

**FILE-13, choosing a file to open.** When a moment or clip is cued:
1. If the open recording contains it, jump there.
2. Otherwise open the original, if it still exists.
3. Otherwise open the newest recording that contains it.

The page lists every recording that contains a clip, with its own cue button, so you can open the edited episode over the raw footage. Deleting an original costs nothing once an edited recording holds its ticks.

**FILE-14, the call on `Host`.** `locate(recordingId, tick)` returns every (file, recording id, tick) that contains that moment, best first by the order above, or nothing if it was cut everywhere. It replaces `findRecording`. `openRecordingId()` stays, and a new `openRecordingContains(recordingId, tick)` answers through the open recording's spans.

**FILE-16, the time index, recorded from the first version.** Every recording stores, for every tick, the real time and the server's game time. It costs a few bytes a tick and can never be added to a recording afterwards, so it is captured from day one (principle 3).
- `locate` uses it as a fallback: a moment whose recording id is unknown, but whose real time falls inside a recording of the same world, is still found.
- Several points of view (2.6) align recordings with it.
- It stays the fallback, never the main route, because real time pauses and jumps where ticks do not.

**Your code (Yours, S):** `moments_cue`, `moments_clip_goto` and the timeline marks of 2.3 call `locate` and `openRecordingContains` instead of comparing ids. `moments_clips_list` gains `locations`: every recording that holds the clip, each with the stretches of it there and whether it was cut. Re-run the carry-over afterwards.

**Tests (chapter XIV):**
- Trim the scripted recording around the pig kill, then cue the kill: the trimmed recording opens at the right tick.
- Cue a moment inside the removed stretch: it reports "cut" and lists the original.
- Trim a cut through the middle of a clip: the clip plays as two pieces and is flagged.
- Combine the trimmed recording with a second one from another world: earlier clips still cue correctly, the boundary switches worlds, and clips from the second recording land after it.
- Delete the original and cue again: the edited recording answers.
- Delete the index file, and separately rename a recording outside the game: the index repairs itself and every cue still works.
- Remove a recording's id from a moment by hand: it is still found by its time.

### 1.3 Export queue and background export (Spec, L)
- **EXP-10, the queue:** exports can be queued (scene, range, preset, output name) and run one after another. The queue survives a restart, and each job can be reordered, paused or removed. Each job reports progress, and a desktop notification says when the queue is done.
- **EXP-13, exporting without losing the game:** the queue can run in a second, hidden game process. It is started from the running game with the same game folder, mods and settings, opens each recording, renders the queue and exits. The game you are in stays playable, and a run can go on overnight.
  - The worker is told the job list through a file and reports progress back through it.
  - If a second process cannot start (memory, or a launcher that forbids it), the queue runs in the current game as before and says why.
  - The worker never writes to the game's settings, saves or recordings other than its outputs.
- **MCP:** `export_queue_add`, `export_queue_list`, `export_queue_remove`, `export_queue_start`, with an option for the background worker. Task 2.2 depends on this.

### 1.4 Camera path preview, and baking the Smart Camera (Spec + Yours, M)
- **EDIT-8, the preview:** while editing, draw the camera track's path through the world: a line along the interpolated positions, a mark at each keyframe, and an arrow for look direction at intervals. Editor only, never in exports, with a toggle.
- **Smart Camera stretches are drawn too.** Its path depends on the recording, so it is drawn from the last time that stretch was played, in a lighter colour, and marked stale when a setting has changed since. A "preview" button plays the stretch at up to 20 times speed with rendering paused, to draw the path without waiting.
- **EDIT-10, baking (Yours + Spec):** a Smart Camera stretch can be turned into ordinary camera and field-of-view keyframes, one every few ticks, simplified to the fewest keyframes that stay within a tenth of a block and half a degree of the original. The result can then be adjusted by hand. Baking goes through undo, and the original Smart Camera keyframe is kept, disabled, so it can be restored.
  - **Yours:** `SmartCameraSolver` gains a fast run that steps a range and returns the poses, without moving the viewer.

### 1.5 Markers in the world (Spec, S)
- **REC-32:** a marker recorded with a position appears in playback as a coloured ring at that spot, with its description on hover. Clicking it jumps to the marker's tick.
- Never drawn in exports. Can be hidden.

### 1.6 Chat commands (Spec + Yours, S)
- **REC-7:** a client-side `/dailies` command:
  - `start`, `finish`, `pause`, `resume`, `cancel`
  - `mark [colour] [save position] [description]`
  - `moment <label>` logs a custom moment there and then, the fastest way to flag "that was good" mid-play (Yours, calling `moments_event_add`'s code)
  - `config` opens the settings
  - `hide` toggles Dailies' interface
- Commands work on any server, since they never reach it.
- A key binding does the same as `/dailies moment` with a default label, so it works without typing.
- PLAY-4's "Dailies' own" commands then has something to point at.

### 1.7 Smaller restorations (Spec, M in total)

| Item | Requirement |
|---|---|
| Distant Horizons | New CMP-1. With Distant Horizons installed, playback shows its far terrain. The playback world uses its own level-of-detail store, so the real world's data is never touched. |
| More look settings | Add to chapter V: scoreboard, action bar and hotbar toggles; blocks, entities, players and beacon beams on or off. |
| Letterboxing | Add to chapter V: an aspect ratio can change the actual frame (black bars in the editor, the true size in export), not only draw a guide. |
| Recordings list | Add to REC-5: search by name, world, player or date; sort by date, name, length or size; folders; stars; rename; edit the name and preview image. |
| A bin, not delete | Add REC-9: deleting a recording, scene or moments session moves it to `dailies/bin/` for 30 days (a setting), from where it can be restored. Emptying the bin is the only permanent delete, and asks first. |
| Player list | Add to PLAY-3: a panel listing every player in the recording at the current tick, to follow, ride along with or hide. |
| Medal.tv upload | Add to chapter I: **not included**, a deliberate choice. Video goes out as files, ready for any service. |

---

## Phase 2: add what the series needs

### 2.1 Create and Aeronautics moments (Yours + Spec, L)
The series is about craft, and nothing logs them yet.

**Two sources, one event.** The companion knows for certain what the server did (a craft was assembled, broke apart, or a train left), so when it is present it is the authority. The player's own game detects the same things by observation, so they still work on servers without the companion. When both report the same thing within 5 seconds, they become one event with both sources listed, as deaths already do.

| Event | Detected by the game | Sent by the companion | Base weight |
|---|---|---|---|
| `craft_assembled` | A Sable sub-level appears within 16 blocks of the player within 5 s of them using an assembler block or running `/sable assemble` | When Sable creates a sub-level, to the player who assembled it and players within the nearby radius | 40 |
| `craft_boarded` | The player first rides or stands on a craft, once per craft per session | | 15 |
| `craft_first_flight` | A craft the player is aboard rises 8 or more blocks above the ground beneath it, the first time for that craft | | 50 |
| `altitude_record` | The player, aboard a craft, beats their highest altitude in this world by 10 or more blocks; at most once a minute | | 35 |
| `speed_record` | The same for craft speed, by 10% or more | | 30 |
| `craft_lost` | A craft the player was on or near is removed while moving fast, or loses most of its blocks | When a sub-level is destroyed, split or loses over half its blocks, with the cause where known | 55 |
| `contraption_assembled` | A Create contraption entity spawns within 8 blocks of the player | | 20 |
| `train_departed` | | A Create train the player is aboard leaves a station for the first time | 30 |
| `train_arrived` | | That train reaches its first destination | 25 |

- **Firsts:** each type gets a first-of-kind bonus as usual.
- **Suggested camera:** craft events get a wide chase that targets the craft itself (2.13); `craft_lost` gets "Director, quick cuts"; train events get a tripod fly-by.
- **Spec:** the event table in chapter IX, the weights, and the companion's new events in chapter XII.
- **Test:** the balloon flight in the scripted run must log `craft_assembled` (from both sources, merged), `craft_first_flight` and an altitude record.

### 2.2 From clip to finished video (Yours + Spec, M)
- **MCP `moments_clip_to_scene` (Yours):** make a scene named after the clip, set its export range to the clip, and add the suggested camera.
- **EXP-14, export presets (Spec):** named sets of export settings, editable, with these built in:
  - **YouTube 1080p60** and **YouTube 4K60:** H.264 or AV1, high bitrate, stereo AAC.
  - **Shorts and Reels:** 1080×1920 vertical, 60 fps, up to 60 s.
  - **Editing master:** ProRes 422 HQ or DNxHR HQ in MOV, with separate audio tracks (2.5), for a video editor.
  - **Smallest:** AV1 at moderate quality, for sharing.
- **Vertical framing (Yours):** with a vertical preset, the Smart Camera frames for the vertical frame: composition and auto zoom use the export's aspect ratio. Clips made for Shorts suggest a closer chase.
- **Page (Yours):**
  - "Make scene" and "Export" on each clip, choosing a preset.
  - Tick boxes with "Export selected", which queue one job per clip through 1.3.
  - Output files are named from the clip title and date.
- **`Host` gains:** `createScene(name, start, end)`, `queueExport(scene, preset, file)` and `exportPresets()`.
- **Spec:** describe the flow in chapter XI, the presets in chapter VI, and the `Host` methods in chapter XV.

### 2.3 Moments on the editor timeline (Yours + Spec, M)
- **EDIT-9:** when a recording opens, its moments appear on the timeline: events as small marks coloured by kind, clips as bands, voice as a strip per speaker showing when they talked. They obey the ignore list and have a toggle. Hovering shows the description, or the words said; clicking jumps there.
- **Yours:** `Moments.timelineFor(recordingId)` returns the marks, bands and voice strips for a recording, through `locate` from 1.2, so it works for edited recordings too.
- **Spec:** the core draws them.

### 2.4 Captions (Yours + Spec, M)
- **EXP-11, files:** an export can write SRT and WebVTT files of what was said in its range.
  - Lines are timed in video time, so speed, timelapse and freeze keyframes are allowed for, using the same mapping as EXP-2.
  - Each line can be prefixed with the speaker's name.
- **EXP-15, burned in:** captions can be drawn into the video itself, in a chosen font, size, colour and outline, at the bottom or wherever the frame has room. One style, "word by word", shows each word as it is said, as short-form videos do.
- **Word timing (Yours):** transcription asks the Whisper server for word timestamps as well as segments, and stores them. Without word timings (an older segment), words are spread evenly across their segment.
- **Yours:** `Moments.transcriptFor(recordingId, fromTick, toTick)` returns the lines and words.
- **Spec:** the core maps the times, writes the files and draws the captions.

### 2.5 Separate audio tracks (Spec, M)
- **EXP-12:** an option writes extra audio alongside the mixed track, all the same length and in sync to the sample:
  - the game sound alone
  - each speaker's voice alone, named after the speaker
  - audio keyframes (music) alone
- **Both forms:** separate WAV files beside the video, and extra named tracks inside MKV and MOV. The "Editing master" preset uses both.
- **Why:** an editor can lower music under speech or cut one person's mic without re-exporting.

### 2.6 Several points of view (Spec + Yours, L)
For a multiplayer series, friends' recordings of the same session are footage too. Both steps below are part of this plan.

- **Step 1, linking (FILE-15, PLAY-18):**
  - **Sharing:** "Share session" packs a recording and its moments session into one file. "Import" adds a friend's pack to your library and their moments to your page, marked as theirs.
  - **Linking:** recordings made on the same server whose times overlap are linked automatically, aligned by the time index (FILE-16), using the server's game time so their clocks agree to the tick.
  - **On the page:** a clip lists every linked recording that covers it, each with its own cue button, and the session strip shows everyone's events, one row per person.
  - **Export:** the same stretch from every point of view, in one queued batch.
- **Step 2, switching (PLAY-19, EDIT-11):** a scene can hold linked recordings. A "point of view" keyframe changes which recording is shown from that tick, with a cut or a short crossfade. The camera, Smart Camera and look settings carry across the switch, and the timeline shows the linked recordings as lanes.
  - The core plays one recording at a time and switches by jumping the other to the aligned tick, so a switch is a seek, which PLAY-15 keeps short. A preload of the next recording's nearest snapshot makes it near instant.
- **Needs:** friends running Dailies. Without them, everything here still works on your own recordings.

### 2.7 Group shots for the Smart Camera (Yours + Spec, M)
- **CAM-10:** a Smart Camera keyframe can name several players.
  - Framing uses their combined bounds, the aim point is their centre, and auto zoom fits the group.
  - The director gains a "two-shot" for exactly two subjects, and favours the speaker when voice is recorded.
  - A subject who leaves (logs off, dies, goes out of range) drops out of the group; the shot falls back to whoever remains.
- **Suggested camera:** conversation clips suggest an orbit framing the speakers.
- **Changes:** `SmartCameraSettings.target` becomes a list of targets of any kind from 2.13 (Yours), and the editor panel and `smart_camera_add` accept several targets (spec appendix A).

### 2.8 Disk space over a long playthrough (Spec + Yours, M)
- **REC-8, the storage panel (Spec):** shows how much space recordings, moments audio, exports and the bin use, per world.
- **Retention options:**
  - Move recordings older than N days to the bin, unless starred or holding a clip that was exported.
  - Remove a voice segment's audio once it is transcribed, or after N days.
  - Warn when free space falls below a threshold, and pause recording before the disk fills, with a notice.
- **Defaults:** keep everything, warn below 20 GB, pause below 5 GB.
- **Yours:** the audio half lives in Moments.

### 2.9 Laughter and shouting (Yours, M)
The transcription filter throws laughter away, yet a burst of laughter is often the best clip signal there is.

- **Without AI, always on:** track each speaker's loudness and its rolling baseline.
  - Log a `voice_burst` when a speaker stays 12 dB or more above their baseline for at least 1.5 s.
  - Log a stronger one when two or more speakers do so within 3 s.
  - Weights 30, plus 10 per extra speaker. At most one every 20 s.
- **With AI on:** a small sound classifier runs on the player's own computer, with no server, and labels each burst: laughter, cheering, screaming or shouting. The label becomes the event's subject, so laughter can score above shouting.
  - Pick a model whose licence allows bundling, and that runs on a CPU in well under real time. Bundle it in the mod, not downloaded.
  - Segments the transcription filter drops as non-speech are kept, marked `non_speech`, and also offered to the classifier.
- **Spec:** add to chapter IX.

### 2.10 Reaching the MCP server from another machine (Yours + Spec, M)
- **MCP-8:** settings `mcpBind` (default `127.0.0.1`), `mcpToken` (generated when remote access is switched on) and `mcpAllowedClients` (addresses or ranges, default the local network).
  - Binding to anything but localhost requires the token, sent as a Bearer header, and the client's address to be allowed. Repeated bad tokens from one address lock it out for a minute.
  - **HTTPS:** when bound beyond localhost, the server uses HTTPS with a certificate it generates on first use. Its fingerprint is shown in the settings and the log, so a client can pin it.
  - The Origin and Host checks still apply, widened to the bound address.
  - The page asks for the token once when it is not on localhost and remembers it in that browser.
- **Why:** an agent on another machine can drive clip-finding while the game runs on the PC, safely.

### 2.11 The page, live (Yours, S)
- **Live updates:** the page gets a small event stream of its own (`GET /live`, not part of MCP), so new events, clips and transcripts appear the moment they happen. It becomes a second-screen view while playing. It falls back to refreshing every 10 s if the stream drops.
- **Event pictures:** when an event scoring 50 or more happens during live play, the game saves a small picture of the screen, about 320 pixels wide, beside the session. The page shows it on the event and on its clip. Costs a fraction of a frame and a few kilobytes. A setting turns it off.

### 2.12 Other players' privacy (Spec + Yours, S)
- **MOM-36:** a list of players whose voice is never captured or transcribed (`momentsVoiceExcluded`), editable in settings and over MCP. Their audio is still heard live and still recorded for playback if voice recording is on; it simply never reaches Moments or Whisper.
- **MOM-37:** the settings screen and the page say plainly that, with AI on, voice audio goes to the Whisper address set. Nothing is sent anywhere with AI off.
- **Spec:** add both to chapter IX, and a sentence to chapter I that capturing other people's voices is the user's responsibility to agree with them.

### 2.13 Smart Camera targets: craft and any entity (Yours + Spec, M)
A Smart Camera can film a craft itself, not only a player on it: an Aeronautics aircraft on its own, a balloon drifting with nobody aboard, a contraption, a horse.

- **CAM-11, what can be a target:** a player, any other entity (by its id), or a Sable craft (by its unique id, which Sable keeps for the craft's whole life and which every snapshot carries).
  - **Picking one:** in the editor, a list of the players, crafts and nearby entities present at the current tick, crafts shown by their name, plus "pick in the world" to click one. Over MCP, `target` accepts a player name, an entity id, or `craft:<unique id or name>`.
  - A keyframe's targets may mix kinds (with 2.7), for example a plane and the player chasing it.
- **Filming a craft:**
  - Framing uses the craft's whole bounds, and the aim point is its centre.
  - "Behind" is measured from the direction the craft is travelling, falling back to the way its nose points (the pose's forward direction) when it is slow or hovering. A heading setting chooses travel, nose, or a fixed compass bearing.
  - The craft's own blocks never count as blocking the view, as for framed vehicles today.
  - The director's tripod fly-by suits craft well: placed ahead on the flight path, it lets the craft pass the camera.
- **When the target goes:** a craft that is removed (disassembled, destroyed, out of range) holds the camera on its last position for the shot's transition time, then follows whoever was aboard, or holds still if nobody was. A player target that logs off does the same.
- **`Host.Crafts` gains:** `crafts(level)` (each craft's unique id, name and bounds), `craftById(level, uniqueId)`, `craftId(craft)` and `craftForward(craft, partialTick)`. Carry the answers from the Sable code with 0.3.
- **Moments:** craft events (2.1) suggest a camera that targets the craft itself.
- **Test:** a Smart Camera on the balloon with nobody aboard follows it up its climb, framed whole, in chase and orbit, and holds still when it is disassembled.

### 2.14 Settings screen with dropdowns (Spec, M)
The game's standard settings screen shows every choice as a "< value >" button that has to be clicked through one value at a time.

- **CFG-4:** Dailies has its own settings screen, opened from the mods list, the pause menu and `/dailies config`, with:
  - **A dropdown for every choice:** enum settings, codecs, presets, encoders, languages. No click-through buttons anywhere in Dailies, in the settings, the editor or any other screen.
  - **Search** across every setting's name and description.
  - **Groups** that fold, following the settings chapter's groups.
  - **Descriptions** from each setting's comment, shown on hover.
  - **Reset** to default, per setting and per group, with changed settings marked.
  - **Numbers** as a slider with a box to type into, both checked against the range.
  - **Addresses** (the Whisper and Ollama URLs, the MCP bind address) checked as they are typed, with a "Test" button that says whether the server answers.
- The underlying settings file stays NeoForge's standard one (CFG-1), so it can still be edited by hand.

---

## Phase 3: fix the Dailies folder

### 3.1 Video encoding: every platform, every GPU (Spec + Folder, L)
- **Problems:**
  - EXP-1 promises H.265, but the bundled FFmpeg build has no software H.265 encoder.
  - That build has no AMD hardware encoding, and only Windows natives are bundled.
  - EXP-8 says nothing about platforms or licences.
- **Fix: build FFmpeg ourselves.** A reproducible build script (in CI, and runnable locally) produces FFmpeg for Windows x64, Linux x64 and arm64, and macOS arm64 and x64.
  - **Licence:** LGPL configuration, with no `--enable-gpl` and no `--enable-nonfree`.
  - **Hardware encoders:** NVIDIA (NVENC), AMD (AMF on Windows, VA-API on Linux), Intel (Quick Sync and VA-API) and Apple (VideoToolbox). H.264, H.265 and AV1 wherever the hardware supports them.
  - **Software encoders:** SVT-AV1 and libaom for AV1, libvpx for VP9, Opus and AAC for audio, plus FFmpeg's own ProRes and DNxHR for editing masters. **No software H.264** (decided): H.264 comes from hardware encoders only, and a machine without one exports AV1 or VP9, or an editing master. The presets of 2.2 fall back the same way and say so.
  - **Jars:** each platform gets its own jar so no download carries the others, plus one all-platform jar for convenience.
  - The Java side may keep the Bytedeco wrappers (taken under Apache 2.0), pointed at our natives.
- **EXP-1:** the codec list is whatever the machine and the bundled build offer. Each choice says whether it runs on hardware or software. The fastest is picked by default.
- **EXP-8:** state the platforms and the licences, ship each library's licence text and a notice, and keep the build script with the source so anyone can rebuild the natives (which the LGPL asks for).
- **Decided: LGPL.** x264 and x265 would need a GPL build and make Dailies GPL; hardware encoders on every GPU vendor cover H.264, H.265 and AV1 instead.
- **Decided: no OpenH264.** Cisco's patent licence only covers binaries fetched from Cisco, so software H.264 is left out rather than bundled or downloaded.

### 3.2 to 3.14

| # | Problem | Fix | Side | Size |
|---|---|---|---|---|
| 3.2 | The main spec does not link the Create and Aeronautics note. | Link it from chapter VII's lede, chapter XV's file table and chapter I's "what is being built". | Spec | S |
| 3.3 | No README in the folder. | Add `README.md`: what Dailies is, the reading order (README, spec chapter I, the rest of the spec, the tech note), what each folder holds, where the work comes from, how to build each part, and who to ask. | Folder | S |
| 3.4 | `Host.Crafts` and the voice feed have nothing behind them. | Done by 0.2 and 0.3; update chapter XV's file table and the core's step list. | Spec | S |
| 3.5 | Renderer compatibility is missing. Create draws contraptions through Flywheel, Aeronautics uses Veil, and shader packs and Sodium-style renderers change how frames are produced. | New chapter, "Other renderers", with requirements: playback and export are correct with Flywheel, Veil, Iris or Oculus shader packs, and Sodium or Embeddium; contraptions are fully updated in every exported frame at any export speed; tests for each combination. | Spec | M |
| 3.6 | Appendix B's interactions example is made up. | Run a two-player session in the earlier build, copy a real `meeting` and `group_formed` record, and replace the example. | Folder | S |
| 3.7 | The companion has no build wrapper or README, and keeps its old ids. | Rename it to `dailies_companion` (mod id, channel and event class) before anyone else installs it, on both sides together. Add a Gradle wrapper and a README (install, `/moment` usage, settings, every event). | Yours | S |
| 3.8 | The spec has few measurable targets. | Add budgets the scripted run measures (below). | Spec | M |
| 3.9 | The scripted run does not cover anything added by this plan. | Add a test step per new requirement, and list what still needs two players or a person. | Spec | M |
| 3.10 | Nothing runs the scripted test automatically. | Add a CI job on a self-hosted runner with a GPU (the owner's PC is enough) that builds every part and runs the scripted test on each change, keeping the log, screenshots and measurements. | Folder | M |
| 3.11 | A recording made on a server plays some things wrong when the player's own game numbers a registry differently from the server: the owner heard his jetpack's wings play the wrong sound. Sounds, particles, menu types and other registry entries travel as numbers, and NeoForge's handover of the server's numbering at join is in the recording but skipped in playback, so the numbers are read against the player's own. | While a recording plays, use the server's numbering it holds (the frozen registry handover NeoForge sent at join), as NeoForge does on joining a server, and put the player's own back when playback ends or fails. It needs no mod's help and fixes recordings already made. A test records on a server whose mods differ from the client's (one sound-adding mod fewer, or ids shifted) and checks the right sound, particle and menu in playback. | Spec | M |
| 3.12 | In the owner's pack on a server (20 November), an export after watching a recording drew no far terrain and ran under 60 frames a second at 4K. Distant Horizons renames the folder Dailies gives it onto itself before opening it, and Windows refuses while a file in it is open. Distant Horizons can leave the playback database open after closing a level, because a worker thread opens a connection as the level closes. So when the export swapped worlds, the reopen failed on every frame, about 4.5 times a frame, each with a stack trace: 37,750 in four minutes, a 253 MB log, and 7 to 17 ms a frame where 0.1 to 0.7 was usual. | On Windows, Distant Horizons is given a directory junction (`dailies/dh/links/...`) that leads to the folder. Renaming the junction onto itself renames only the junction, and the database stays one file shared by the old connection and the new. A world copy that has changed upstream is refreshed only once nothing holds it. | Spec | S |
| 3.13 | In the same session, recording logged 1,182 Lootr errors in about a minute: "Tried to fillUpdateTag on the client side". A snapshot asks every block entity near the player what it would send of itself. On a client, Lootr's chests, barrels, shulker boxes, pots and suspicious blocks give a complete answer (opened, and by whom), but log an error each time: one per Lootr container in sight, every two seconds. | `compat/lootr`: with Lootr installed, a filter on Lootr's logger drops that one message while a snapshot is being taken. Lootr's answer, so the recording, is unchanged; anything else it logs, and the same message at other times, still gets through. | Spec | S |
| 3.14 | Twice in the owner's pack (20 November), an export froze the game for good, about three seconds in. Both times the playhead stood partway through, so the export first rebuilt the world, and Distant Horizons reopened its far terrain. Then, waiting for the far terrain to settle, the export asked Distant Horizons on the game's thread whether it held each chunk. Distant Horizons answers by waiting, with no limit, for the chunk's data to load, and while it was still opening a 1 GB store and taking in 453 chunks, that wait never ended. Both frozen exports were H.265, and an H.265 export from the start of the recording ran fine, so the codec is not thought to be the cause. | The questions go to a thread of their own (`Dailies-Far-Terrain-Check`), and the game's thread only reads the answers. An ask with no answer after 5 s ends the wait for the far terrain, said once in the log. | Spec | S |

### Budgets for 3.8

| Measure | Target |
|---|---|
| Recording overhead | Under 3% lower frame rate and under 1 ms added per frame, on the test world at 1080p |
| Recording size | Under 150 MB per hour of normal play, excluding resource packs and voice |
| Jump within a recording | Under 2 s to any tick, under 0.5 s within the current segment |
| Opening a recording | Under 5 s to the first frame for a two-hour recording |
| Moments | Under 0.2 ms per tick of tracking; transcription keeps up in real time with AI on |
| Export | Faster than real time for 1080p60 H.264 on hardware encoders |
| Craft accuracy | Craft within 0.3 blocks of their live position in playback; standing passengers within 0.25 blocks of the deck (MOD-4) |
| Smart Camera | Same director shots on every replay of a stretch; under 0.5 ms per frame |

---

## Phase 4: finish

| # | Task | Size |
|---|---|---|
| 4.1 | Re-run the carry-over script after every "Yours" change, and confirm `reference/` still compiles. | S |
| 4.2 | Check every requirement id is unique and every test step points at real ids. Validate the HTML and links of both documents. | S |
| 4.3 | The earlier build's name, its author and its package names may stay wherever they identify that work. | S |
| 4.4 | Read both documents end to end, and fix anything ambiguous, contradictory or left to guesswork. | M |
| 4.5 | Brief the reviewer: list what is behaviour and what is provided source; include the encoder licences from 3.1 and the codec patent position. | S |
| 4.6 | **Gate: the review is done and its changes are made.** | — |
| 4.7 | Keep this plan in the folder. | S |

---

## Decisions (made 17 November 2026)

| Decision | Chosen |
|---|---|
| Encoder licence | LGPL build, with hardware encoders on every GPU vendor |
| Software H.264 | None. Hardware H.264 only; without it, AV1, VP9 or an editing master |
| Retention defaults | Keep everything; warn below 20 GB free; pause recording below 5 GB |
| Voice audio once transcribed | Kept; a setting removes it |
| Remote MCP | Off by default; HTTPS, token and allowed clients when switched on |
| Event pictures | On by default |
| Medal.tv | Left out |
| Dropdowns in the earlier build | Not changed there; Dailies gets them through 2.14 |
| Companion ids | Renamed to `dailies_companion` (3.7) |
| Server resource packs | Stored inside each recording (1.1) |
| Several points of view | Both linking and switching (2.6) |
| Encoder (18 November) | The own LGPL build for every platform, from the FFmpeg workflow on GitHub (3.1) |
| McpCraft and playback (18 November) | McpCraft recognises Dailies only, not Flashback (0.5) |

No decisions are open. New ones found while working go in this table before the work they affect starts.

---

## Suggested order

1. **Phase 0:** all of it, while the earlier build is still at hand.
2. **FILE-16, the time index:** into the spec straight away, since recordings made without it can never gain it.
3. **3.2 to 3.4, 3.7:** quick fixes that make the folder correct as it stands.
4. **1.1 to 1.7:** nothing is lost in the move.
5. **2.1 and 2.2:** craft moments and clip-to-video, the two that matter most for the series.
6. **2.13 and 2.14:** craft targets for the Smart Camera, and the settings screen.
7. **2.3, 2.4, 2.9, 2.11, 2.12:** moments become richer and the page goes live.
8. **3.1:** the encoder build, which takes the longest to set up.
9. **2.5, 2.7, 2.8, 2.10, 3.5, 3.6, 3.8 to 3.10**
10. **2.6:** several points of view, linking then switching.
11. **Phase 4,** ending with the review gate.
