# Dailies: plan for interface in recordings

Players see windows and overlays while they play: a chest they open, a machine's screen, the engineer's goggles' readout. A
recording does not hold any of them today (the spec drops the player's own screens and windows at record time), so an
export shows the world but never what was on the player's screen. This plan records what is drawn during play and draws
it again in playback and exports.

**When it shows (the owner's rule):** only with the scene's **Show HUD** look on **and** the camera in first person,
riding along with the recorder, in the editor's playback and in exports. Never in third person, a free camera, or with
the HUD off.

**What counts as play (the owner's rule):** a screen opened by using something in the world (a block, an item or an
entity) or by the inventory key, during normal play, and the overlays drawn over play. **Everything else is left
out:** settings and configuration, statistics, advancements, the recipe book panel, the pause menu and every other
menu, and title, login, setup and debug screens. Selection wheels used in play (Create's toolbox and wrench wheels) count as play.

Recording works from an allow list, not a block list: a screen is recorded only when Dailies (stage 1) or a compat
(stages 2 to 4) says it is a play screen. The deny list in the decisions below applies only within that allow list,
for server-driven windows that are play screens but draw wrongly in playback.

Sizes as in `PLAN.md`: **S** under an hour, **M** a session, **L** several sessions.

## Progress (19 November 2026)

**Done** means the code, its spec rows and its tests are in and pass. **Not started** means nothing is built yet.

| Step | Status | Notes |
|---|---|---|
| 1.1 Record windows | Done | `dailies:windows` records (`windows/WindowCapture`, `WindowState`); REC-25, and REC-11 names the exception |
| 1.2 Draw them | Done | `windows/WindowLayer`; PLAY-20, EXP-16. Deny list: the stonecutter (its recipes need packets REC-11 drops) |
| 1.3 Jumps, trim and combine | Done | The whole window in each snapshot; unit tests only (`WindowStateTest`) |
| 1.4 The "Open windows" look | Done | `showWindows`, on by default, under Show HUD |
| 1.5 Companion plugin API | Done | `CompanionPlugins` on `dailies_companion:compat`, client side `compat/companion/CompanionCompat`; SRV-6 to 8. First use: the server's list of plugins |
| 1.6 Spec and tests | Done | Display test `WindowSteps`, group `windows`: chest, furnace, villager trades and inventory drawn in first person, absent in third person and flying free. Passed in game |
| 2.1 Client compat API | Done | `api/compat`: a mod registers a `DailiesCompat` and claims screens, menus, window extras, overlays and a server half; Dailies records each claim's state on change and in every snapshot. Claims win over the generic path. The manifest lists the compats used; a missing one is said once. REC-26, REC-27, PLAY-21, PLAY-22, EXP-17, CPT-1 to 3. `gradlew apiJar` builds the API jar (CPT-4) |
| 2.2 Server API | Done | `Claims.serverHalf` on the companion plugin API; the companion's `disabledPlugins` setting; the privacy rule in SRV-9 |
| 2.3 Template compat project | Done | `compats/template` (mod id `dailies_template`): an example screen, an overlay, a server half and a display test; builds with Dailies, not in the game test (CPT-5) |
| 3 Post Rider | Done | Post Rider 1.2.2 carries its own hooks (`client/dailies`), compiled against a copy of the API jar, loaded only with Dailies present. Mailbox drawn by the generic path plus window extras (tab, address, scroll); reading a letter (hand, page, checked against the held letter); writing a letter (text, page, cursor, read-only screen). Display test group `postrider`, passed in game |
| 4.1 Create | Done | `compats/create` (mod id `dailies_create`), a jar of its own beside Create. Every play-time screen, overlay and in-world mark Create 6.0.10 draws: the value settings board, value boxes and their tip, the goggles readout; the sequenced gearshift, threshold switch, elevator contact, station and train assembly, display link, factory gauge, symmetry wand, worldshaper, schematic naming and schematic edit screens; the toolbox and wrench selection wheels; the clipboard; remaining air, the train HUD, the linked controller's bind HUD, toolbox hotbar marks, the blueprint and table cloth row, the track placement hint, Create's status-line messages; the held schematic (tool bar, placed box, deploy preview) and a placed schematic's blocks, stored once in the recording (REC-28, PLAY-23, CPT-7); every outline Create draws in the world (super glue selection and glued areas, the schematic and quill selection, arm, ejector and port targets, chain conveyor, track and zapper previews), placement helper arrows and ghost blocks, and the symmetry mirror. Stage 1 alone: schematic table, schematicannon, linked controller binding, toolbox, crafting blueprint, package port. Stage 1 plus extras: list and attribute filters, package filter, package port address, redstone requester, the schedule with its entry editor, the stock keeper's search, address, order and scroll. Display test group `create`: 29 displays, passed in game. **Not drawn:** Create's client-side particles (the wand's end-rod particles and others), which are not recorded state |
| 4.2 The owner's mods (Create Addons, McpCraft) | Not started | Hooks in the mods themselves |
| 4.3 Jade | Not started | |
| 4.4 Supplementaries, Deep Seas, flight and ride overlays, compasses | Not started | |

Known limits of stage 1, for a compat or a later fix:
- other mods' listeners on window draw events still fire in playback;
- a mod window may write into the playback world's blocks as it is filled;
- the recipe book panel is never shown;
- a mod window already open when recording starts is left out;
- the creative inventory is not recorded;
- the companion has no unit tests of its own (its routing is tested in the core, `CompatRoutesTest`);
- a letter read in playback is drawn only if the recorder's held letter matches the one recorded;
- the template compat is not in the game test;
- the modded test now expects Post Rider loaded, from the pack or `dailies.modJars`.
- the Create compat reads some of Create's private fields by their 6.0.10 names: a rename in a Create update loses that part, logged once;
- Create hides the goggles' readout while a value box shows, so a recording does too.
- in playback the Create compat removes every outline Create works out from the playback camera, and draws only the recorded ones;
- the Create compat's status-line mixin keeps only action-bar lines sent from Create's or Catnip's code (found by a stack walk).
- in playback the Create compat briefly swaps some of the viewer's state while drawing and puts it back (the held item for the toolbox wheel's tip, the view's turn for the wrench wheel, Create's schematic name, Catnip's fade counter);
- the Create compat rebuilds the schedule's entry editor in playback through Create's private fields and methods.

---

## What the modpack draws during play

From a scan of the 116 mods in Clockwork Commonwealth, the pack the series is played in, sorted by hand. The scan flagged 57; most of those were libraries,
mixins into vanilla screens, login, onboarding, shader or debug screens, and are left out below.

**Windows the server drives** (a menu the server opens and fills). One generic mechanism can show all of these (stage 1):

| Mod | Windows |
|---|---|
| Vanilla | chests, barrels, shulker boxes, ender chest, furnaces, crafting, anvil, enchanting, brewing, beacon, villager trading, horse, the player's inventory |
| Post Rider | mailbox |
| ComedyMonkey's Create Addons | animal pen, auto harvester, chest upgrades, fish tank, monster cage, dripstone generator and others |
| Sophisticated Backpacks | backpacks |
| Curios | the curios inventory |
| Corpse | a corpse's inventory |
| Supplementaries | presents, notice board, pulley, cannon, red merchant |
| Create, Create Deep Seas, Pipez, VanillaBackport | schematic table and toolbox; boat engine, electrolyzer; pipe settings; nautilus inventory |

**Screens only the client knows** (opened and filled on the player's side). Each needs a compat:

| Mod | Screens |
|---|---|
| Post Rider | reading a letter, writing a letter |
| Create | **every machine setting**: the value settings board and the value box a block shows (a speed controller's or creative motor's speed and direction, left or right, a gearshift's or clutch's state, a smart chute's or funnel's filter and amount, a mechanical arm's targets, and every other value a block is set with); the machine screens (sequenced gearshift, threshold switch, linked controller binding); train schedules and stations, clipboard, display link, factory panel, package filter |
| ComedyMonkey's Create Addons | dynamo, electric motor, turbine, splitter, Trade Bot stations |
| McpCraft | the job board, the job and factory prompts, job storage |
| Supplementaries, Amendments | blackboard, sign post, doormat, speaker; lectern book editing |
| Create add-ons (Connected, Encased, Tracks, Deep Seas) | sequenced pulse generator, auto clutch, track tuning, sonar |
| Vista | picture tape |

**Overlays drawn over play.** Each needs a compat that records what it showed:

| Mod | Overlay |
|---|---|
| Create | engineer's goggles, the value box over a block being looked at, blueprint and schematic overlays, linked controller, remaining air |
| Jade | what the player looks at |
| ComedyMonkey's Create Addons | jetpack, villager goggles |
| Create Jetpack, Gyroscope Stabilizers, Hypertube, Propulsion, Deep Seas, Coasters | flight controls, joystick, ride and placement overlays |
| Supplementaries, Explorer's and Nature's Compass, Locator Bar, Vista | cannon, slimed and selected-item overlays; search results; the locator bar; viewfinder |

**Left out on purpose:** Xaero's Minimap and World Map and AppleSkin (the owner does not want them in videos), FPS
Overlay, Dynamic FPS, JEI and Inventory Profiles Next buttons and panels, shulker box tooltips, Iris, Simple Voice
Chat's group and setup screens (its icons are already a scene look), Better Advancements,
the compasses' biome lists (menus, not play screens), statistics, the recipe book panel, and every config screen.

---

## Stage 1: standard windows in the core and the companion (Spec + core, L) — Done

1. **Record windows (REC).** The player's own window packets, which REC-11 drops today, are recorded into a stream of
   their own and never replayed into the playback world's packet handling: open screen, container contents, slot
   changes, data slots, close, horse screen, villager offers.
   - The player's inventory (E) opens with no packet. Its opening and closing are recorded on the client.
   - While a window is open, the mouse position, the carried item and the hovered slot are recorded each tick.
     Mouse positions are kept in the window's own units, so a different screen size or GUI scale draws them in the
     right place.
2. **Draw them in playback and exports (PLAY, EXP).** A screen layer builds the window from the recorded menu type and
   contents with the game's own screen classes, and draws it with the pointer and the carried item. It takes no input
   and sends nothing. It is shown only under the owner's rule above.
   - Modded windows go through the same path. A window that cannot be built or drawn this way is left out, logged
     once, and listed so a compat can take it over.
3. **Jumps, trim and combine.** The window open at a tick is part of each snapshot, so a jump into the middle of an
   open chest shows it. Trim and combine carry the stream like any other extension record.
4. **A look, "Open windows",** under Show HUD, on by default. It hides windows while keeping the rest of the HUD.
5. **Companion: the server helper API.** For the player's own windows the server already sends everything, so
   standard windows need no server help. This stage gives the companion a plugin API for later compats:
   - a registered id;
   - data pushed per player over one shared channel (`dailies_companion:compat`);
   - a version, and a way for a client compat to ask for it.
6. **Spec and tests.**
   - Rows in REC, PLAY and EXP; the REC-11 exception; the look in chapter V.
   - One small display test, not a full one: open a chest, a furnace, a villager's trades and the inventory while
     recording, then check in first-person playback with the HUD on that each window is drawn, and in third person
     that it is not. Jumps, trim and snapshots are covered by unit tests.

## Stage 2: the compat API and a template (Spec + core + companion, M) — Done

1. **Client API (`comedymonkey.dailies.api.compat`).** A compat registers:
   - an id and a version;
   - the play screens and overlays it claims;
   - how it records them (its own extension records, as little data as will draw them again);
   - how it draws them in playback;
   - what it holds for a snapshot;
   - a small display test: a scripted step that opens its screens while recording and checks they are drawn in
     first-person playback. Every compat, and the vanilla windows, gets one of these, not a full test.

   Its records carry its version. A recording lists the compats that wrote into it; playback without one plays on
   without that layer and says so once.
2. **Server API.** On the companion's plugin API from stage 1: a compat's server half sends facts only the server
   knows. Players who opted out of voice capture, and the plan's privacy rules, apply to compat data as to any other.
3. **A template compat project:** Gradle, the client and server halves, one example screen and one overlay, and a test
   step. It is copied to start each compat.

## Stage 3: Post Rider as the first compat (Owner's mod, M) — Done

Post Rider has one window and two screens, which makes it a good first test. It is the owner's own mod, so its hooks go into the mod itself, implementing the compat API directly, rather than into a separate compat jar.

1. **Mailbox:** a server-driven window, so stage 1 should already show it. The compat checks that and fixes what stage 1
   cannot.
2. **Reading a letter:** the text is in the letter item the player holds, which the recording already has. The compat
   records which letter was opened, and when, and draws Post Rider's own reading screen from it.
3. **Writing a letter:** the compat records the text as it changes, a few times a second while the screen is open, and
   draws the writing screen with the text as it stood at each tick.
4. **Server half:** none is expected: the client already has the mailbox contents and the letters. If delivery or
   mail state turns out to be server-only, the companion API carries it.
5. **Test:** Post Rider joins the modded test's mods. A small display test opens a mailbox, reads a letter and writes
   one while recording, and checks each screen is drawn in first-person playback.

## Stage 4: the rest of the pack, by value (Compat, L) — Underway (Create done)

In order of how often they appear in a Create let's play:

1. Create: every machine setting first (the value settings board and value box, and the machine screens, so a viewer sees what speed, direction or amount was set), then the goggles overlay, then train schedules and the clipboard.
2. ComedyMonkey's Create Addons (its screens and overlays) and McpCraft (the job board, the prompts and job storage): the owner's own mods. These need no compat jar: the owner can add hooks to the mods themselves, so each implements the compat API directly as a soft dependency (found through `DailiesApi`, as McpCraft already does for playback), records exactly what its screens need, and sends any server-side facts from its own server code.
3. Jade.
4. Supplementaries, Create Deep Seas, the flight and ride overlays, the compasses, Emotecraft's emote wheel.

Each takes the stage 3 shape: what is the least data that draws it again, does the server need to send anything, and
a small display test.

---

## Decisions (made 19 November 2026)

| Decision | Chosen |
|---|---|
| Where compat jars live | Subprojects in this repository: one build, built and tested together with Dailies and the companion |
| Stage 1 for modded windows | Every server-driven window is tried generically; one that misdraws goes on a deny list, or gets a compat |
| The pointer | The recorded mouse pointer and the carried item are drawn over the window |
| Tests | One small display test per compat and for the vanilla windows: the screens are drawn in first-person playback, not in third person. No full comparison tests |
| Left out | Xaero's Minimap and World Map, AppleSkin |
| The owner's mods: Post Rider, ComedyMonkey's Create Addons, McpCraft | Hooks written into the mods themselves: each implements the compat API directly, as a soft dependency, instead of a separate compat jar. Separate compat jars, as subprojects here, are for other people's mods |
