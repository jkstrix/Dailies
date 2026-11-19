/**
 * The compat API: how another mod's play screens and overlays get into Dailies' recordings and are drawn again in
 * playback and exports (specification REC-26, REC-27, PLAY-21, PLAY-22, EXP-17 and CPT-1 to CPT-5). These classes keep
 * their names and meaning from version to version; a mod compiles against Dailies' API jar
 * ({@code dailies-api-<version>.jar}, built by {@code gradlew apiJar}), which holds this package and nothing of
 * Dailies' own workings.
 * <p>
 * <b>What Dailies does already.</b> A window the server opens with a menu type (a chest, a machine) is recorded and
 * drawn by Dailies itself, with the mod's own menu and screen classes. A compat is for what that cannot draw: a screen
 * only the player's game knows (a book being written, a settings board), a window whose screen keeps state the server
 * never sent (a tab, a selection, text being typed), and an overlay drawn over play (a goggles readout).
 * <p>
 * <b>Registering, as a soft dependency.</b> A mod must load and run with Dailies absent, so only one class of its own
 * refers to this package, and it is only touched when Dailies is there. In the mod's client set-up:
 * <pre>
 * if (ModList.get().isLoaded("dailies")) {
 *     MyModDailies.register();   // a class of the mod's own that implements DailiesCompat
 * }
 * </pre>
 * and in that class:
 * <pre>
 * public static void register() {
 *     DailiesCompats.register(new MyModDailies());
 * }
 * </pre>
 * Compile against the API jar as {@code compileOnly}, so it is never packed into the mod and nothing of it is needed at
 * run time without Dailies. Register while the mod loads; Dailies takes registrations at any time from then.
 * <p>
 * <b>What a compat says.</b> {@link comedymonkey.dailies.api.compat.DailiesCompat} gives an id, a version and its
 * claims ({@link comedymonkey.dailies.api.compat.Claims}): screens by class, server windows by menu type, extras for a
 * server window Dailies draws, overlays, and the client half of a server half's data. A claimed screen class or menu
 * type is taken away from Dailies' own window recording; it is the compat's alone.
 * <p>
 * <b>Recording.</b> At the end of each recorded tick Dailies asks each claimed part that is showing for its state: a
 * byte array of the compat's own making, the least that draws it again. It is the whole state, never a change, so
 * Dailies can write it only when it differs from the last one written (at most as often as the part asks), put it in
 * every snapshot, and carry it through jumps, trims and combines without the compat doing anything more. Each record
 * carries the compat's version, and the recording lists the compats that wrote into it with their versions. Records
 * are written only while recording, under the compat's id.
 * <p>
 * <b>Playback.</b> Parts are drawn only under the owner's rule for the recorder's windows: the viewer rides along with
 * the recorder in first person and the scene's "recorder's HUD" look is on, and for screens and window extras its
 * "open windows" look too. Never in third person, with the camera flying free, or with the HUD off. A screen part
 * builds a screen from the state, which Dailies lays out without telling other mods, draws as the recorder's game drew
 * it, and draws the recorded pointer over, in the window's own units. Drawing takes no input and sends nothing: a
 * screen built for playback must never send a message to the server or change the world.
 * <p>
 * <b>Snapshots.</b> What a part holds for a snapshot is its state, as it gave it: every snapshot written while
 * recording holds the state of each part showing then. In playback Dailies plays each record by keeping the newest
 * state of each part, and describes what it holds by writing those states again, so a jump lands with the part as it
 * stood, a trimmed recording starts with it, and a combined one carries each part's records. Because every state is
 * whole, a compat writes no snapshot code of its own.
 * <p>
 * <b>Missing compats.</b> A recording played in a game without a compat that wrote into it plays on without that
 * layer and says so once. A record written by a newer version of a compat is handed to the older one with its version,
 * for it to draw what it can or nothing.
 * <p>
 * <b>Privacy.</b> A part's state stays in the recording: Dailies never gives it to Moments, the transcription server,
 * MCP or anything else. Record only what the recorder saw on their own screen.
 */
package comedymonkey.dailies.api.compat;
