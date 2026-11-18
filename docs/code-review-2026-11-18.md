# Dailies: whole-repository code review

**Date:** 2026-11-18. **Commit:** `69517ab` (master). **Scope:** everything tracked in git: `src/` (about 78k lines in main, 10k in test), `reference/` (17k), `companion/` (1.3k), the build, CI and `tools/`.

**How the review was done**

- It was read-only. No code was changed, and no unit tests, game runs or Gradle tasks were run.
- The code was judged against `design/dailies-spec.html`, Java, Minecraft/NeoForge and general practice.
- The repository was split into eight areas. Each area was read in depth, and every finding was checked against the code and its callers before being kept.
- The findings marked ✔ were then re-checked line by line a second time (the "re-checked" column). The others were confirmed by the area reviewer against the code but not traced a second time.
- **PLAUSIBLE** means the code path is real, but the trigger or the size of the effect needs a runtime check, which is described in each case.

---

## Outcome (added 2026-11-18, after the fixes)

The owner asked for every finding to be fixed. All of them were, on master, except BLD-3, which the owner chose to leave. The table gives the commit that holds each area's fixes. The fixes were checked by compiling only; unit tests and game runs follow as one batch.

| Area | Findings | Commit(s) | Outcome |
|---|---|---|---|
| Path helper | Section 4 item 1 | `8ebbfc5` | `SafePaths`, used by SEC-1, 3, 5 |
| Playback, packs, sound | PB-1..5, SEC-3, L-14..16, L-37 (Feed) | `4963142` | Fixed |
| Library, compat | SEC-1, SEC-5, LIB-1..4, L-6, L-22..24, L-36, L-37 (Links), Section 4 item 6 | `7a76b24` | Fixed |
| Recording, file format | REC-1..4, FILE-1, L-17..19, L-30 | `272208d` | Fixed (`BoundedReader`) |
| Editor, timeline | EDT-1..8, L-20, L-21, L-32, L-34, L-38, L-46 | `b1e36a4` | Fixed; `hermite` added as the owner chose |
| Interface | UI-1, UI-2, L-26..29, L-33, L-35, L-45, L-47 | `22de9f0`, `3f2ce12` | Fixed; `InputRouter`, library views split |
| MCP, moments, companion, settings | SEC-2, SEC-4, SEC-6..9, L-1, L-2, L-4, L-5, L-25 | `1924993`, `43a6973` (spec text) | Fixed |
| Certificate | L-3 | `1924993`, `14c591b` | Fixed: server-only certificate, random password kept in the config folder, owner-only files |
| Export | EXP-1..8, L-7..13, L-31 | `5ecf006`, `6eff868`, `ac65256` | Fixed; the encoder is made ready off the client thread by every way an export starts |
| Build, CI, self test | BLD-1, BLD-2, TST-1..3, L-40..44 | `8e3bb44`, `2e69491`, `d8ce8c6` | Fixed; the self test is its own mod and jar |
| Repository hygiene | L-39 | `43a6973` | Fixed |
| Machine paths | BLD-3 | none | Left as it is, by the owner's choice |

### Tested (2026-11-18, afternoon)

- `gradlew test`: 648 tests pass, none skipped.
- The first game runs (`runTestClient`, `runTestModded`) failed four steps. Three were self test checks the fixes had made stale: snapshots now also share a chunk's light on its own (`cc91d5b`); waits count the game's ticks (`2e69491`), so slowed playback must be timed by the clock; and library tools take only recordings inside the folder (`7a76b24`), so the pack recording is copied in. The runs also found two real faults: a player's own recordings of other worlds were linked as points of view (PLAY-18 says different players), and items dropped in the void were sometimes not left to fall in playback. All five are fixed in `5f98255`.
- The full run (`runTestModded` with the bestiary, generated terrain and camera films, 1 hour) passed every one of those, and failed six steps:
  - **Points of view:** the export batch still sometimes misses the friend's recording in the modded game. Cause: since `521071e`, a snapshot that stores chunks afresh flushes the part that begins its segment before the segment's times are taken, so that segment carried the previous segment's times, or the previous recording's. Fixed in `19271d0`. Not yet run in a game.
  - **Trains:** a jump back puts the train 1.41 blocks from the live game. Earlier runs of the same code had it at 0.14, so it depends on timing. A train rebuilt from a snapshot starts at a standstill, because Create's add-train message carries no speed. That may explain it, but it is not proved, so nothing is changed yet.
  - **Bestiary:** 32 items wrong and 5 not staged. It was 33 before the review.
  - **Camera films:** 15 hard faults. The walk into the Nether also timed out after 40 seconds. That timeout passed in every earlier run and the log does not say why it failed; the step now reports the player, the portal and the server's view when it gives up (`19271d0`).
  - **BUD-2:** recording size is 354 MB an hour (target 150) in the village on foot.

---

## 1. Summary

The codebase is in good shape for its size and age. Several things are done properly throughout:

- the append-only file format with recovery
- bounded queues with back-pressure
- tokens compared in constant time and the MCP server bound to loopback
- zip-slip protection in share packs
- no `innerHTML` on the moments page
- careful GL state handling
- unit tests that inject the clock and zone

The problems found fall into a few patterns, and most can be fixed with one shared helper per pattern (section 4).

| Severity | Count |
|---|---|
| High | 7 |
| Medium | 40 |
| Low | 47 (grouped in tables) |

### Fix first

| # | ID | Problem | Why first |
|---|---|---|---|
| 1 | SEC-1 | `replay_delete` can bin any file on the disk, and the bin then deletes it for good | Data loss, reachable by any MCP client or a prompt-injected agent |
| 2 | SEC-2 | `config_get` returns the MCP token in clear text, and `config_set` can rewrite the MCP access settings | Leaks the remote-access secret into agent transcripts |
| 3 | SEC-3..6 | Paths taken from recordings and share packs are used without checks (pack hash, DH folder, `audio_file`, session id) | A shared recording is untrusted input |
| 4 | EXP-1 | The encoder (about 76 MB) is unpacked again every session, and the overwrite fails while another game is using it | Breaks the background export worker on Windows |
| 5 | EXP-2 | A `%` in the export name fails every parallel export | Default settings on NVIDIA |
| 6 | EDT-1 | A freeze stops the camera too, against the spec | Core editing feature does the wrong thing |
| 7 | PB-1 | A jump back keeps light from later in the recording | Same bug family as the last three commits, through another path |

---

## 2. High-severity findings

### SEC-1. `replay_delete` can move any file into the bin, where it is later deleted for good ✔
- **Where:** `src/main/java/comedymonkey/dailies/library/Library.java:115-123` (`find`), `:205-227` (`delete`). `library/Bin.java:195-213` (`put`). Reached from `mcp/tools/LibraryTools.java:82-84,190`.
- **What happens:** `find` accepts any absolute path, or a relative path containing `..`, as long as it names a regular file. `delete` hands it to `Bin.put`, which runs `Files.move(original, target)`. Nothing checks the `.dailies` extension or that the file is inside the recordings folder.
- **Effect:** `Bin.restore` refuses items that "came from outside the Dailies folder", and housekeeping deletes them after `binDays`. So one tool call such as `replay_delete {"recording":"C:/Users/x/.minecraft/saves/World/level.dat"}` takes the file away, Dailies cannot put it back, and it is gone for good 30 days later.
- **Fix:** delete only files whose normalised path `startsWith(Recorder.folder())` and that end in `.dailies`. Use the same rule in every tool that writes, moves or deletes. Add a unit test.

### SEC-2. The MCP token can be read and the MCP access settings changed over MCP ✔
- **Where:** `src/main/java/comedymonkey/dailies/mcp/tools/CoreTools.java:118-152,162-174`. `core/CoreHost.java:87-94`. `config/DailiesConfig.java:102`.
- **What happens:**
  - `config_get` with no key describes every setting, and `describe()` always includes `value`, so `mcpToken` comes back in clear text.
  - `config_set` goes through `CoreHost.saveSetting`, which has no denylist, so any caller can set `mcpBind`, `mcpAllowedClients` and `mcpToken`.
  - Plain loopback needs no token, so any local process can do this.
- **Effect:** the remote-access password ends up in agent transcripts and in cloud model logs. An agent misled by text in chat or a transcript could open the server to the LAN.
- **Fix:**
  - Add a `secret` flag to `Setting`, and have `config_get` report "set" or "not set" for it.
  - Refuse changes to `mcp*` settings through `config_set`; they belong on the settings screen.
  - Enforce a minimum token length when one is set by hand.

### EXP-1. The encoder is unpacked again every session; on Windows the overwrite fails while another game is using it ✔
- **Where:** `src/main/java/comedymonkey/dailies/export/Encoder.java:84-90`.
  ```java
  if (!Files.exists(file) || Files.size(file) != entry.getSize()) {
      Files.copy(jar, file, REPLACE_EXISTING);
  }
  ```
- **Why the size check never matches:** `JarInputStream` only knows an entry's size when its local header carries it. All 46 entries in `build/resources/main/encoder/ffmpeg-windows-x86_64.jar` are deflated with the data-descriptor flag set (checked with `zipfile`), so `getSize()` returns -1 and the size test always fails.
- **Effect:**
  - The first export of every session rewrites about 76 MB on the client thread.
  - While the background export worker or a second game has `ffmpeg.exe` or `avcodec-*.dll` open, `REPLACE_EXISTING` cannot delete the target, `program()` throws, and the export fails.
  - Two games unpacking at the same time write the same files in place.
- **Fix:**
  - Write a stamp file next to the unpacked encoder (mod version plus the resource's size or hash) and skip unpacking when it matches.
  - Unpack to a temporary name and `ATOMIC_MOVE` into place.
  - Hold a `FileLock` on `encoder/<platform>/.lock` while unpacking.

### EXP-2. A `%` in the export name fails every parallel export ✔
- **Where:** `src/main/java/comedymonkey/dailies/export/ParallelVideoSink.java:87,276`. `export/NamePattern.java:37-38` keeps `%`.
  ```java
  folder = parent.resolve(output.getFileName() + ".dailies-parts");
  command.add(folder.resolve(index + "-%06d.ts").toString());
  ```
- **What happens:** FFmpeg's segment muxer reads the whole path as a numbering template, so a name such as `100% run` makes the template invalid.
- **Why there is no fallback:** the muxer only opens after the first frame. The 400 ms is-it-running check and `parallelTrial` (which writes to `-f null`) both pass, then lane 0 exits a few frames in and the whole export fails instead of dropping back to one encoder.
- **When it bites:** `exportParallelEncoders` defaults to 3, so this happens with default settings on NVIDIA H.264.
- **Fix:** don't put user text in the template path: `parent.resolve(".dailies-parts-" + UUID.randomUUID())`. Or escape `%` as `%%`.
- **To confirm at runtime:** export a scene named `100% test`.

### EDT-1. A freeze stops the camera too, though the spec says the camera keeps moving ✔
- **Where:** `src/main/java/comedymonkey/dailies/edit/TimeMap.java:204-240` (`cameraTickAt`), `export/Export.java:583`, `editor/EditorSession.java:198`.
- **What happens:** the time map holds the recording's tick still during a freeze. Export evaluates every track at that same tick, the camera track included, so the camera and shake stop with the world. `cameraTickAt`, which works out where the camera should be during a freeze, is never called from main code; only `TimeMapTest` uses it.
- **Spec:** line 461 says "Holds the world still for a stated time while the camera track carries on moving". Appendix line 1416 says the same.
- **Fix:** sample the tracks that drive the camera at `timeMap.cameraTickAt(seconds, track)`, in export and in playback. Add a test that renders frames across a freeze and checks the camera pose keeps changing.

### PB-1. A jump back into a kept world keeps light from later in the recording
- **Where:** `src/main/java/comedymonkey/dailies/playback/Feed.java:542,551,644-650,881-902`. Writer side: `capture/Snapshots.java:607-637`.
- **What happens:** when a chunk's blocks are unchanged but its light changed, later snapshots write the stored chunk K followed by a light packet L2. While loading, `track()` keeps `cleanChunks[C] = K` as L2 is applied, so the bookkeeping says the chunk is plain K when it is really K plus L2.
- **Failure:** load at snapshot S2, then jump back to S1, which holds plain K. `alreadyThere(K)` matches and the chunk is kept with L2's light. S1 sends no light packet for that chunk, so the `keptNow` fix from 2915024 never runs. This is the same "a level too bright" symptom, in the other direction.
- **Fix:** record the light applied on top of each kept chunk (`Map<Long,String> lightOn`). Count a chunk as already there only when its pending light matches. This also lets jumps skip re-sending light that is already correct.
- **Related, PB-2 (Medium):** the trim path `Snapshots.java:735-737` writes only the reference to K, so trimmed recordings lose L2 for the same reason.

### SEC-3. A recording's pack hash is used as a file path *(High because recordings are shared)* ✔
- **Where:** `src/main/java/comedymonkey/dailies/playback/PackPlayback.java:329-332,266`. `file/RecordingManifest.java:191` keeps `sha256` exactly as parsed.
  ```java
  Path target = folder.resolve(pack.sha256 + ".zip");
  if (!Files.isRegularFile(target) || Files.size(target) != pack.size) file.extractFile(pack, target);
  ```
- **What happens:** a crafted recording (for example one arriving in a share pack) can use an absolute or `..` hash. That lets it create folders and temporary files outside `pack-cache`. If an existing file at that path happens to have the stated size, it is loaded as a server resource pack with no hash check, because the size check skips extraction.
- **Fix:** accept only `^[0-9a-f]{64}$` when parsing the manifest, and check `target.normalize().startsWith(folder)`.

---

## 3. Medium-severity findings

### Security and untrusted input

| ID | Where | Problem | Fix |
|---|---|---|---|
| SEC-4 ✔ | `reference/.../moments/voice/TranscriptionWorker.java:134-145` | `audio_file` from `dialogue.jsonl` (copied unchanged from a friend's share pack by `MomentsShare.importSessions`) is resolved and read with no containment check. Any readable file can be sent to the transcription server. A UNC path makes Windows open an SMB connection to that host (PLAUSIBLE NTLM exposure). | Reuse the check `VoiceRetention.java:71-74` already does (`normalize()` + `startsWith(session.dir)`). Require `audio/[A-Za-z0-9_-]+\.wav`. Mark imported records as not pending. |
| SEC-5 | `compat/distanthorizons/DistantHorizonsCompat.java:235-239` | `distantHorizonsFolders` from the manifest is resolved against the game folder and accepts absolute, `..` and UNC paths. A shared recording can make the client copy and open a remote sqlite file. | Accept only relative paths that normalise inside the game folder, and only for recordings made on this machine. |
| SEC-6 | `reference/.../moments/storage/MomentsStorage.java:151-154` | The session-id check rejects `/ \ ..` but not `:`. On Windows `root.resolve("D:x")` can point at another drive (PLAUSIBLE: confirm with `Path.of("E:\\m").resolve("D:x")`). A `:` elsewhere in the id throws `InvalidPathException`, which becomes an HTTP 500. | Allow only `[A-Za-z0-9._-]{1,80}` and check that the parent of the resolved path is the root. |
| SEC-7 | `reference/.../mcp/McpHttpServer.java:47-50,333-341,381` | The 15 s timeout applies to each read, and the head is read one byte at a time before the token check. Four slow clients (any allowed LAN address in remote mode, or any local process) take all 4 workers. Long tools (combine/trim 250 s, POV share/import 600 s) also hold workers. | Give each request one overall deadline. Cap connections per IP. Cap the drain loop by time. Run long tools on their own executor. |
| SEC-8 | `reference/.../moments/track/GameplayTracker.java:633-649`, `compat/companion/ServerMoments.java:81-95` | Any server can send `dailies_companion:event` packets (up to 32 KB each) with no rate limit. Each packet is held in memory, written to disk, published and scored. | Add a token bucket per session (for example 5 per second) and cap the stored `data`. |
| SEC-9 ✔ | `config/DailiesConfig.java:112,116` | `momentsWhisperUrl` and `momentsOllamaUrl` default to `http://192.168.188.50:…`, a private LAN address. A user who turns AI on without changing them sends everyone's voice, unencrypted, to whatever device has that address on their network. | Default both to empty, which the setting descriptions already describe as "off". The spec repeats these values (2 occurrences), so it needs the same change. That is the owner's decision. |

### Recording and the file format

| ID | Where | Problem | Fix |
|---|---|---|---|
| REC-1 ✔ | `capture/Spool.java:40` (`setDaemon(true)`), `capture/Recorder.java:138-147` | On LoggingOut, `finish()` only queues the final job. Nothing joins the writer thread at shutdown, so closing the game from inside a world (PLAUSIBLE: the reviewer traced `Minecraft.destroy()` to `System.exit`) kills the daemon writer. Up to 192 MB of queued work, the name, the players and the end time are lost, and the file is left `.dailies.recording` for recovery. | Wait on the finish future with a bounded timeout at game shutdown (a NeoForge shutdown event or a JVM hook), or make the thread non-daemon until `last()` has run. |
| REC-2 ✔ | `capture/Recorder.java:740` | `codec.encode(made.get(i))` on the writer has no per-chunk try/catch. Before 521071e it was inside `writeChunks`' catch-all. One chunk that fails to encode (for example a mod's update tag changed mid-serialisation, PLAUSIBLE) sets `Spool.failure` and stops the whole recording. | Catch each chunk on its own, log once, leave its name null and skip it. The chunk is described afresh at the next snapshot anyway. |
| REC-3 | `capture/Snapshots.java:651` | The chunk packet copies the section data and light, but its heightmap tags share the live `long[]` (`ClientboundLevelChunkPacketData` keeps `getRawData()`). The writer serialises arrays the game thread is changing, so stored heightmaps can come from a later tick or be torn, which shows as wrong rain or snow columns in playback. | Clone each heightmap array on the game thread (about 37 longs each). |
| REC-4 ✔ | `capture/Snapshots.java:292-294` | `ClientboundLightUpdatePacket` runs `CHUNKS.remove(...)`, which throws away the stored form, so the chunk is fully re-described. That undoes cc91d5b for exactly the neighbouring chunks the server sends light updates for. | `computeIfPresent(key, s -> s.withLightChanged())`. Measure the result with the existing REASONS log ("light" count). |
| FILE-1 | `file/Packing.java:47-51`, `file/Records.java:140-146`, `file/TimeIndex.java:114`, `extras/RecordedSkins.java:118-122` | Lengths read from a file go straight to `new byte[length]`. CRC32 does not stop a crafted file, so a length near 2 GB gives `OutOfMemoryError` (not caught by the IOException handlers), and `ticks + 1` can overflow. `Records.Reader.name()` and `TimeIndex.Table.read` throw unchecked exceptions instead of `RecordingFormatException`. | One `BoundedReader` helper: check each length against the bytes left and an absolute cap, and throw `RecordingFormatException` (see section 4). |

### Playback

| ID | Where | Problem | Fix |
|---|---|---|---|
| PB-2 | `capture/Snapshots.java:735-737`, `playback/Feed.java:111-114` | Trimming writes the stored chunk without the light on top of it (see PB-1). | Return both references, or none when light was applied on top. |
| PB-3 ✔ | `playback/Playback.java:351-363` | `reloadIndex` carries over from an earlier `jump()` that has not been handled yet; it is reset only at `:492` and `:882`. Example: skip +10 s then −10 s in one long frame and playback lands on 1200 instead of 1150. Dragging the playhead with `scrubTo` takes the same path. | Start each `jump()` from `reloadIndex = -1` and decide again. |
| PB-4 ✔ | `playback/Playback.java:352-359` | With `again` set, `reloadIndex = index` is assigned before the run-up `index--`. `refine()` and exact re-jumps therefore start without the 60-tick run-up that exists to avoid drift. | Move the `again` assignment after the run-up adjustment. |
| PB-5 | `playback/Feed.java:566-576`, `file/RecordingFile.java:361-370` | Every jump reads, decompresses and decodes every soft-kept chunk again, only to get its block-entity tags. Nothing is cached. | Stored chunks are content-addressed and immutable, so a bounded LRU `name → block entities`, or a "has none" flag, is safe. |

### Export

| ID | Where | Problem | Fix |
|---|---|---|---|
| EXP-3 | `export/Export.java:793-806`, `sound/ExportSound.java:903`, `ParallelVideoSink.java:519` | `finish()` runs on the client thread and blocks on: the writer join (120 s), the encoder exit (120 s), the parallel join (which copies every byte), and the sound mux (up to 30 min). A long 4K export with sound freezes the window ("Not Responding") and cannot be cancelled. | Move the finishing stages to a worker thread and have `frameStart` poll and show a "finishing" state. |
| EXP-4 | `export/Export.java:388-434` | After the sink (the FFmpeg processes) is created, `new TimeMap` and `new FramePool` (up to 1 GB per frame at 16K) can throw with nothing calling `abort()`. FFmpeg then stays alive, holding an NVENC session and a half-written file until the game exits. | Wrap everything from creating the sink to `active = true` in a catch of `Throwable` that aborts the sink and the sound. |
| EXP-5 | `export/QueueRunner.java:79-82,356-361`, `ExportQueue.java:457` | If `frame()` throws, or writing a job's end fails, the job stays RUNNING with this game's pid. `recover()` skips own-pid jobs, so the job can be neither removed nor resumed until restart. | On any abnormal stop, mark the job FAILED (retrying the write), and let `recover` fail own-pid jobs when nothing is running. |
| EXP-6 | `export/Encoder.java:262,343,447-451`, `QueueRunner.java:771` | Hardware trials (up to 20 s each), `parallelTrial` (run inside a `computeIfAbsent` lock) and `Thread.sleep(400)` per mode all run on the client thread when an export starts. | Warm `choices()` and `program()` on a background thread at startup. Race `process.onExit()` against a short timeout instead of sleeping. |
| EXP-7 | `export/Encoder.java:424-427`, `ParallelVideoSink.java:502-505` | The soundless intermediate file still gets `-movflags +faststart`, and the sound mux rewrites the file with faststart again, so with parallel encoding the video passes through disk about four times. | Skip faststart for the intermediate file. Better: feed the PCM into the parallel join, so the video is copied once. |
| EXP-8 | `export/Export.java:737-741` | `while ((free = pool.take(50)) == null)` on the render thread has no deadline and no cancel check. An encoder that stalls without exiting freezes the game for good. The persistent path, by contrast, gives up after 120 s. | Add a deadline and check `cancelRequested`. |

### Editor and timeline

| ID | Where | Problem | Fix |
|---|---|---|---|
| EDT-2 ✔ | `editor/EditorSession.java:261` | The shake phase is `tick/20 × frequency(tick)`, so its rate is f/20 + (tick/20)·f′. When the frequency changes late in a recording, the phase races (about 500× too fast for a ramp from 2 to 4 at tick 10000), and a HOLD change makes the view jump. | Integrate the phase, φ = ∫f dt, per segment, or measure the phase from the keyframe's own tick. |
| EDT-3 | `editor/CameraSteps.java:49`, `reference/.../smartcamera/SmartCameraSolver.java:25` | Follow-camera and Smart Camera state is keyed by Track identity, but `EditHistory.step` copies every Track on every edit. Any edit, even one unrelated to the camera, resets the springs and the director in the middle of a shot. | Key by a stable track id (a UUID that `copy()` keeps). |
| EDT-4 | `edit/Evaluator.java:57` | An orbit around a fixed point aims from the feet position (`atan2(height, distance)`), but the eye is 1.62 higher. The line of sight passes 1.62 blocks above the centre, about 11° off at distance 8. The orbit around an entity gets this right (`CameraSteps.java:216`). | Use `atan2(height + EYE_HEIGHT, distance)` and update `EvaluatorTest:202-207`. |
| EDT-5 ✔ | `editor/EditorSession.java:137,159` | `savedRevision = revision` is set before the save runs, and a failure is only logged. After one IOException, autosave stops and `closing()` skips the save, so edits are lost (EDIT-7). | Mark the revision saved only after the save succeeds (CAS from the saver thread). |
| EDT-6 | `editor/EditorSession.java:265`, `ui/KeyframeForms.java:159` | `looks.roll` is meant for when no keyframe sets roll, but it does nothing without a camera track and is added on top when there is one. New keyframes capture the roll with looks.roll already included, so it is counted twice. | Apply it only when no keyframe gives roll, and capture keyframes with it subtracted. |
| EDT-7 | `edit/Evaluator.java:276` | TIME_OF_DAY (0 to 24000) is interpolated as a plain number. Going from 22000 to 2000 runs the sky backwards through noon. | Treat the channel as periodic and move forward by default. |
| EDT-8 | `editor/SmartCameraDriver.java:64,318-334` | `target()` throws when the named player isn't present, and `computeIfAbsent` doesn't cache exceptions. The result is a WARN with a stack trace every tick, and the `== null` branch can never run. | Return null and try again later, or store the UUID when the keyframe is made. |

### Library, compat and the export worker

| ID | Where | Problem | Fix |
|---|---|---|---|
| LIB-1 | `Dailies.java`, `library/Library.java:58-72`, `ExportLog.java`, `Bin.java` | The hidden export worker is a second game sharing `dailies/`. It runs `housekeeping()` (bin expiry, retention, voice retention) and writes `exported.json`. Bin, ExportLog and Stars each load their file once and rewrite the whole list, so each game overwrites the other's changes. This breaks EXP-13 ("nothing written but its exports and its reports"). | Skip housekeeping when `Worker.active()`. Report exports through the queue file, or re-read under a `FileLock` before writing. |
| LIB-2 | `compat/create/CreateCompat.java:154-158,181-185` | At most 64 contraption or bearing angles are stored per tick, but `lastWritten` is updated before the room check. Number 65 onwards is marked written and never stored, and it is the same ones every tick because iteration order is stable. | Size the buffer from the count, or grow it. Update `lastWritten` only after writing. |
| LIB-3 | `library/LibraryIndex.java:290-291` | A `.dailies` file that can't be read is re-parsed on every refresh (at least every 400 ms), and the whole `index.json` is rewritten each time. | Keep a negative cache keyed by path, size and mtime. Set `changed` only when an entry really changed. |
| LIB-4 | `library/LibraryIndex.java` `locate`/`locateClip` | Every miss forces a full folder walk under the index lock. `Moments.timelineFor` calls this once per moment, so hundreds of walks can happen for one trimmed recording. | Rate-limit forced refreshes. Use `walkFileTree` with `BasicFileAttributes` instead of 4 stat calls per file. |

### UI

| ID | Where | Problem | Fix |
|---|---|---|---|
| UI-1 ✔ | `ui/Form.java:69-84` | `holdsFocus()` doesn't check that the focused box belongs to this form, so clearing any form submits a box being typed into in another. The players card rebuilds when a player comes or goes (`EditorScreen.java:684`), so typing "120" into a tick box can commit "12". The pick form's shape includes `currentTimeMillis()/800`, which clears keyboard focus every 0.8 s. | Clear focus only when it is in this form's rows. Build the pick shape from entity ids, not the clock. |
| UI-2 | `ui/SettingsScreen.java:54` vs `RecordingsScreen.java:742`, `EditorState.java:273`, `ExportForms.java:952` | Only one of the four settings-save paths uses a single-thread saver. The others use `Util.ioPool()`, so saves can finish out of order, leaving the older value written. `SAVING.remove(name)` also drops a newer pending value. | One shared `SettingsWriter` (single thread, `remove(name, value)`) for all four. |

### Build, CI and tests

| ID | Where | Problem | Fix |
|---|---|---|---|
| BLD-1 ✔ | `.github/workflows/scripted-test.yml:12-14,27` | The self-hosted runner on the owner's PC runs on `pull_request`. There is no remote today, but once the repo is public, a fork's PR runs its own `build.gradle` on that PC. | Drop `pull_request` for this job, or require approval (an environment or label) and a same-repository check. |
| BLD-2 ✔ | `tools/ffmpeg/sources.lock:21-31`, `.github/workflows/ffmpeg.yml` | Every source's sha256 is `-` and `build.sh:333` refuses unpinned sources, so the FFmpeg workflow cannot pass. Its `macos-13` job also uses a runner image GitHub retired in December 2025 ([changelog](https://github.blog/changelog/2025-09-19-github-actions-macos-13-runner-image-is-closing-down/)). | Run `./build.sh lock` and commit the pins. Move the Intel macOS build to `macos-15-intel`. |
| BLD-3 ✔ | `gradle.properties:12`, `build.gradle:184,200-212` | `dailies.modJars` is an absolute `E:/` path. On another machine the derived `exclude mcpcraft-*.jar` still removes McpCraft from the copy, while the missing jar is not added. The task is a `Sync`, so it also deletes any McpCraft already in `run-modded/mods`. The selftest then skips McpCraft and reports PASSED (the Gradle behaviour is PLAUSIBLE; confirm with `--info`). | Move machine paths to `~/.gradle/gradle.properties`. Fail `installTestMods` when an override jar or the mods folder doesn't exist. |
| TST-1 | `selftest/*` (103 `skip(` calls) | Many skips turn real failures into skips (`CraftSteps:129,132,165,199`, `McpCraftSteps:55-84`, `CameraRoute:273,962,…`). Nothing checks that the expected mods are loaded in `testModded`, and CI looks only for `PASSED`. A broken mods folder or a seat regression shows as green. | When the mod is present, a failure fails. Pass an expected-mods list to `testModded`. Report the skip count in CI. |
| TST-2 | `selftest/SelfTest.java:505-507` | `sleepTicks` is `Thread.sleep(ticks*50)`. It is used 221 times, plus 40 raw sleeps, often right before a state check. Under load, fewer ticks pass than assumed. Tick-based waits already exist but are private (`BestiarySteps.waitTicks`, `RealWorldSteps.ticks`). | Move a tick-counting `waitTicks` into `SelfTest`. Use `nanoTime` in `waitFor`. |
| TST-3 | `selftest/` shipped in the main jar | 86 classes (1.4 MB uncompressed, 26% of class bytes) ship to players. They are dormant without `-Ddailies.test.script`, but they include code that runs level-4 commands, deletes folders and calls `System.exit`. Test hooks also live in production classes (`CraftTracker.forgetRecordsForSelfTest`, `Moments.injectVoiceForSelfTest`, which has no callers). | Give `selftest` its own source set and jar, used by the dev runs and copied into `run-modded` by `installTestMods`. |

---

## 4. Patterns across the code, and the fix for each

Most of the findings above come from a few habits. Fixing each habit once, in one place, closes whole groups of findings and stops new ones.

1. **Untrusted paths.** SEC-1, 3, 4, 5, 6 all resolve a string from a file or a tool call against a folder without checking where it lands. Add one helper, used everywhere a path comes from a recording, a share pack, `dialogue.jsonl` or an MCP argument:
   - `SafePaths.within(Path root, String untrusted)`: reject absolute, UNC and drive-relative paths, `normalize()`, and require `startsWith(root)`.
   - `SafePaths.name(String, Pattern)` for things that are names, such as hashes and ids.

   `SharePack.unpack` and `Bin.restore` already check correctly; copy their rule.
2. **Untrusted lengths.** FILE-1 covers 4 places. A `BoundedReader` over `ByteBuffer` that checks each length against the bytes left and an absolute cap, and throws `RecordingFormatException`, makes a hostile or truncated file fail cleanly. Fuzz-style unit tests (random truncations and bit flips of a fixture recording) would pin this.
3. **Long work on the client thread.** EXP-3, EXP-6, EXP-8 and the 76 MB unpack. Use one pattern: start the work on a worker, poll its `CompletableFuture` from `frameStart`, and show progress. The library screens already do this well (load off-thread, then a staleness token); export should do the same.
4. **One writer per file.** UI-2 (settings), LIB-1 (bin, export log, stars across two processes) and EXP-1 (encoder files across two processes). Use a single-thread writer within a process. Across processes, use a `FileLock` with re-read before write, or keep the worker read-only as EXP-13 intends. `ExportQueue` already does this correctly and is the model.
5. **Identity of things that get copied.** EDT-3 (Track identity across undo copies) and the hash-of-selection check (L-28). Give copied objects a stable id, or compare with a revision counter, rather than relying on object identity or `hashCode`.
6. **Reflection per tick.** `SableCompat.java:371-382` (four `getMethod` calls per craft per tick) and `AeronauticsCompat.java:69` (six `getMethod` plus `setAccessible` per burner). Resolve them in `lookUp()` as `MethodHandle`s and turn the feature off after the first failure, as `CreateCompat` already does.
7. **Test gaps on pure logic.** The bugs in PB-1, PB-3, PB-4, UI-1 and EDT-5 are all map, index or state logic. Moving them into small pure classes, as `Ruler`, `Checks` and `TimeMap` already are, makes them unit-testable. Largest pure-logic classes with no test:

   | Lines | Class | Lines | Class |
   |---|---|---|---|
   | 627 | `mcp/McpHttpServer` | 229 | `edit/JsonFields` |
   | 573 | `smartcamera/DirectorSolver` | 213 | `moments/analysis/ClipSummarizer` |
   | 389 | `moments/track/ProximityLogic` | 205 | `mcp/tools/CoreTools` |
   | 342 | `mcp/tools/LibraryTools` | 194 | `mcp/McpArgs` (validates MCP input) |
   | 339 | `moments/analysis/ClipBuilder` | 181 | `moments/storage/MomentsStorage` |
   | 312 | `moments/storage/MomentsSession` | 156 | `library/Links` |
   | 285 | `smartcamera/OrbitSolver` | 146 | `library/Sharing` |
   | 279 | `smartcamera/ChaseSolver` | 89 | `capture/Spool` |

   Also untested: the jump and segment choice in `Playback.jump`, Feed's `cleanChunks`/light bookkeeping, a `TimeIndex` round trip, hostile input to `Records.Reader`/`Packing.unpack`, `Library.find`/`delete` staying inside the folder, and `McpHttpServer` request handling (403/401/429/413 paths).

---

## 5. Low-severity findings

### Security hardening
| ID | Where | Note |
|---|---|---|
| L-1 | `reference/.../mcp/McpHttpServer.java:444-446` | The moments page sends no `Content-Security-Policy: frame-ancestors 'none'` or `X-Frame-Options`, so it can be framed for clickjacking. Add a strict CSP (`default-src 'self'`). |
| L-2 | `reference/.../mcp/HostRules.java:177-192`, `McpHttpServer.java:469-489` | Origin is checked by host only (any `localhost` port is accepted) and `Content-Type` is not checked, so a page on another local port can POST `text/plain` without a preflight. Require `application/json`. |
| L-3 | `reference/.../mcp/ServerCertificate.java:44,177-178` | The self-signed certificate has `CA:TRUE` with no path length or name constraints, and the keystore password is the fixed `"dailies"`. A user who trusts it trusts an unrestricted root. Issue a leaf certificate (`CA:FALSE`, EKU serverAuth). |
| L-4 | `McpHttpServer.java:352-376` | Writes on `/live` sockets have no timeout. A dropped listener's thread stays blocked until the server stops (PLAUSIBLE). Close the socket when LiveHub drops the listener. |
| L-5 | `reference/.../mcp/McpProtocol.java:102,136` | Any JSON value is echoed back as `id`. Accept only string, number or null. |
| L-6 | `library/SharePack.java:42` | The unpacked size limit is 64 GB, against packs described as "a few hundred MB". Cap it by usable disk space and the number of entries. |

### Robustness and correctness
| ID | Where | Note |
|---|---|---|
| L-7 | `export/Export.java:801`, `Encoder.java:494-497` | The result of `writer.join(120s)` is ignored, so a slow encoder can produce a shorter file reported as success. `exitValue()` after an interrupt throws. |
| L-8 | `export/GpuCapture.java:402-426` | `close()` unmaps buffers even when `awaitTermination` timed out, and `deliver` reads the static `pool`, so a leftover copier can read an unmapped view (PLAUSIBLE native crash). |
| L-9 | `ParallelVideoSink.java:553`, `ExportSound.java:242` | `*.dailies-parts/`, `*.dailies-picture.*` and `*.dailies-sound.pcm` left by a crash are never cleaned up. Names are unique, so "the next export of the same name" never comes. Sweep at startup. |
| L-10 | `export/Worker.java:178-191` | PowerShell's stderr is not drained, and stdout is read to EOF before `waitFor(15s)`, so the timeout never applies. |
| L-11 | `export/ExportQueue.java:587` | `MalformedInputException` (an IOException) escapes the "start again" path, so a queue file in the wrong encoding breaks the queue for good. |
| L-12 | `export/Encoder.java:128-139` | `encoders()` ignores the exit code and caches an empty result for the session. |
| L-13 | `sound/ExportSound.java:810-813` | The WAV header's size is cast with `(int)`, so it wraps beyond 2 GiB (about 3.1 h of 48 kHz stereo). Write RF64. |
| L-14 | `playback/Playback.java:865-876` | One `try` around `closing(); feed.close(); file.close()`. If the first throws, the file stays open and locked on Windows. |
| L-15 | `sound/VoicePlayback.java:65-67,287-298` | Up to 0.5 s of recorded voice keeps playing after a pause or a jump. Stop the sources in `pause()` and when a jump lands. |
| L-16 | `mixin/packs/FontManagerMixin.java:228-237` | The copy of the current fonts is taken at `apply` TAIL, so during a reload off-thread lookups still get closed font sets (PLAUSIBLE). Also clear it at HEAD. |
| L-17 | `capture/Recorder.java:477-484` | A writer failure while paused goes unnoticed, and markers added meanwhile are dropped. `sharedRecord`, `putFile` and the DH job report `bytes() = 0` to back-pressure. |
| L-18 | `file/Container.java:78-83`, `RecordingWriter.java:33-37` | The channel or container leaks if the first write fails, keeping the file locked on Windows. |
| L-19 | `mixin/capture/LocalPlayerMixin.java:37` | Draws from the client level's RNG on every one of the player's own sounds, even when not recording. |
| L-20 | `editor/PointOfView.java:255,360` | The deduplication key includes the tick, so it logs a warning and adds one set entry per tick (72k an hour). |
| L-21 | `edit/Evaluator.java:288-297` | The smooth tangent ignores the neighbours' modes, giving a kink where SMOOTH meets LINEAR and an overshoot after HOLD. |
| L-22 | `library/Bin.java:296-317` | If one delete fails partway through `empty`/`expire`, entries already deleted on disk stay in the list until restart. Save in a `finally`. |
| L-23 | `library/LibraryIndex.java:263-272` | `Files.walk` stops the whole refresh on one unreadable subfolder. Use `walkFileTree` with `visitFileFailed → CONTINUE`. |
| L-24 | `mixin/sable/SableMotionMixin.java`, `SableTimingMixin.java` | A thread-local flag is set at HEAD and cleared at RETURN. If `handle` throws, it stays set (PLAUSIBLE). Use try/finally (`@WrapMethod`). |
| L-25 | `companion/.../DailiesCompanion.java:54-55,77-79` | JSON cut at 32767 characters becomes invalid and is silently dropped by the client, while `/moment` reports success. |
| L-26 | `ui/RecordingsScreen.java:569-595,1059-1068` | A details preview still loading when the screen closes registers a `DynamicTexture` that is never released. Bump `detailsLook` in `removed()`. |
| L-27 | `ui/RecordingsScreen.java:555` | The Storage view's shape includes `pending.size()`, so Tab loses focus after every saved setting. |
| L-28 | `ui/EditorScreen.java:566-570` | Selection changes are detected by `hashCode()` (a sum, so it collides), which can leave a stale error in a box. Compare a revision counter. |
| L-29 | `ui/Controls.java:398,403` | `tell()` on an ioPool thread writes non-volatile notice statics read by the render thread. Send it through `Minecraft.execute`. |

### Performance
| ID | Where | Note |
|---|---|---|
| L-30 | `capture/Recorder.java:741`, `RecordingWriter.java:79`, `Packing.java:25,32` | Every fresh chunk is hashed twice on the writer. There is a new `MessageDigest`, `Deflater` and 64 KB buffer per blob. Add a `putShared(data, hash)` overload and reuse one digest and one deflater per thread. |
| L-31 | `export/ParallelVideoSink.java:436` | An extra copy of every frame (12 MB at 4K) on the writer thread. Hand over the pooled frame instead. |
| L-32 | `ui/TimelinePanel.java:327-341` | Any edit rebuilds the whole `TimeMap` (two evaluations per tick) when a speed, timelapse or freeze track exists. That is about 288k evaluations for a 2-hour recording, during a drag (PLAUSIBLE 30-60 ms). Rebuild only when those tracks change. |
| L-33 | `ui/TimelinePanel.java:551` | Every keyframe is drawn, including those off screen. Binary-search the visible range. |
| L-34 | `edit/Evaluator.java:289,294` | `blendValues()` is allocated inside the per-channel loop. |
| L-35 | `ui/RecordingsScreen.java:475,1378` | `chosenEntries()` (about 12 times a frame) and folder counts walk the whole library every frame. Cache them in `rebuildRows()`. |
| L-36 | `compat/create/FlywheelLooks.java:107-121` | With blocks hidden, every block entity in range is re-queued every frame (PLAUSIBLE). Track the ones already removed. |
| L-37 | `playback/Feed.java:70,624`, `library/Links.java:40,57` | `chunkOfStored` and `Links.KNOWN` grow without bound for the session. |
| L-38 | `edit/EditHistory.java:199` | Each undo step copies every scene and track, and Looks goes through a JSON round trip. Memory grows with steps × keyframes. Copy only what changed (persistent structures). |

### Repository hygiene and consistency
| ID | Where | Note |
|---|---|---|
| L-39 ✔ | `gameplay.log` (tracked, 531 KB, contains local user paths); about 4.4 MB of `gametest-*.log` ignored only by `.git/info/exclude` | Add `*.log` to `.gitignore` and `git rm --cached gameplay.log`. |
| L-40 | `gradle/wrapper/*.properties`, `.github/workflows/*` | No `distributionSha256Sum`, no Gradle dependency verification, and actions pinned to tags rather than SHAs, which matters more on a self-hosted runner. |
| L-41 | `tools/ci/scripted-run.ps1:16-25` | `$LASTEXITCODE` is ignored, so a crash after PASSED counts as a pass. `push` plus `pull_request` tests the same commit twice on the one GPU. |
| L-42 ✔ | `selftest/SelfTest.java:104-108` | The `bestiaryOnly` mode stops by throwing `Failure("only the bestiary was run, and it passed")`, so a passing run is reported as FAILED. That is deliberate, but CI can't tell it from a real failure. Give it its own verdict. |
| L-43 | `selftest/` | `difference(BufferedImage, BufferedImage)` is copied three times. There are four versions of `jump(SelfTest,int)`. Methods run to 481 lines (`LibrarySteps.run`). Add a `SelfTestSupport` class and named sub-steps. |
| L-44 | `src/test/.../YamnetTest.java:126-144`, `ui/ProbeTest.java:40-49` | A wall-clock assertion (under 500 ms) in a unit test, and a free-port race. |
| L-45 | `ui/TimelinePanel.java:830`, `ui/KeyframeForms.java:338`, `ui/RecordingEditScreen.java:311` | Undo step names and one error are hard-coded English. Every literal lang key in `ui/` is present in `en_us.json`. |
| L-46 ✔ | `design/dailies-spec.html:1414-1416` | The MCP appendix lists `hermite` as an interpolation, but `Interpolation.parse` rejects it. The spec or the code needs updating. |
| L-47 | `ui/KitScreen.java` / `ui/EditorScreen.java`; `ui/RecordingsScreen.java` (1630 lines) | About 150 lines of input routing and focus handling are copied in two places. One screen holds three views. Extract an `InputRouter` and split the views. |

---

## 6. What is done well

These are worth keeping as the project's own house style.

- **File format:** append-only container, CRC per block, a fast path through the trailer index, and a walk-and-truncate recovery, so a crash costs only the torn block. Chunk deduplication is content-addressed with SHA-256.
- **Back-pressure everywhere:** the recording spool is bounded by bytes and pauses capture rather than growing, and FramePool, the parallel lanes, PngSink and ReadbackRing are all bounded, with a spare slot so the end signal always fits.
- **MCP server basics:**
  - token from `SecureRandom` (32 bytes), compared with `MessageDigest.isEqual`, never logged, with lockout per address
  - bound to 127.0.0.1 by default
  - Host and Origin checks against DNS rebinding, and `Origin: null` rejected
  - allowlist checked before any byte is read
  - HTTPS with a pinnable fingerprint
- **Moments page:** no `innerHTML`, `encodeURIComponent` on paths, the token sent in headers only, and a strict filename regex on `/pictures`.
- **External processes:** argument lists (no shell), absolute paths, every child's output drained, and caption text drawn by the game rather than passed to FFmpeg.
- **Editing maths:** a correct Steffen monotone cubic, angles unwrapped consistently, `TimeMap` with prefix sums plus Gauss-Legendre and binary-search inverses, and an atomic copy-on-write undo history with correct coalescing.
- **Optional mods:** gated by `isModLoaded`, optional mixin configs `required:false` with `@Pseudo` targets, failures logged once.
- **Unit tests:** an injected clock, zone and locale, seeded randoms, `@TempDir`, `Future.get()` instead of sleeps, and a real assertion in all 440 tests.
- **Logging for measurement:** the per-recording cost breakdown, the per-jump timings and the per-chunk "why it changed" reasons make performance work evidence-based.
