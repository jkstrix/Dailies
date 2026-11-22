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

Sizes: **S** under an hour, **M** a session, **L** several sessions.

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
| 1.7 Books, lecterns and signs | Done | A compat of Dailies' own (`dailies:vanilla`, `windows/vanilla/`): reading a written book, a lectern, writing in a book and quill (and signing it), editing a sign or hanging sign, drawn with the game's own screens, read-only; pages stored once. In singleplayer the book still pauses the game, but the recording keeps time with the world still (`dailies:still` ticks, REC-36), so playback shows the book as long as it was open. 6 displays in the `windows` group, passed in game; the recording, playback, moments and export groups pass with it. The creative inventory stays out |
| 2.1 Client compat API | Done | `api/compat`: a mod registers a `DailiesCompat` and claims screens, menus, window extras, overlays and a server half; Dailies records each claim's state on change and in every snapshot. Claims win over the generic path. The manifest lists the compats used; a missing one is said once. REC-26, REC-27, PLAY-21, PLAY-22, EXP-17, CPT-1 to 3. `gradlew apiJar` builds the API jar (CPT-4) |
| 2.2 Server API | Done | `Claims.serverHalf` on the companion plugin API; the companion's `disabledPlugins` setting; the privacy rule in SRV-9 |
| 2.3 Template compat project | Done | `compats/template` (mod id `dailies_template`): an example screen, an overlay, a server half and a display test; builds with Dailies, not in the game test (CPT-5) |
| 3 Post Rider | Done | Post Rider 1.2.2 carries its own hooks (`client/dailies`), compiled against a copy of the API jar, loaded only with Dailies present. Mailbox drawn by the generic path plus window extras (tab, address, scroll); reading a letter (hand, page, checked against the held letter); writing a letter (text, page, cursor, read-only screen). Display test group `postrider`, passed in game |
| 4.1 Create | Done | `compats/create` (mod id `dailies_create`), a jar of its own beside Create. Every play-time screen, overlay and in-world mark Create 6.0.10 draws: the value settings board, value boxes and their tip, the goggles readout; the sequenced gearshift, threshold switch, elevator contact, station and train assembly, display link, factory gauge, symmetry wand, worldshaper, schematic naming and schematic edit screens; the toolbox and wrench selection wheels; the clipboard; remaining air, the train HUD, the linked controller's bind HUD, toolbox hotbar marks, the blueprint and table cloth row, the track placement hint, Create's status-line messages; the held schematic (tool bar, placed box, deploy preview) and a placed schematic's blocks, stored once in the recording (REC-28, PLAY-23, CPT-7); every outline Create draws in the world (super glue selection and glued areas, the schematic and quill selection, arm, ejector and port targets, chain conveyor, track and zapper previews), placement helper arrows and ghost blocks, and the symmetry mirror. Stage 1 alone: schematic table, schematicannon, linked controller binding, toolbox, crafting blueprint, package port. Stage 1 plus extras: list and attribute filters, package filter, package port address, redstone requester, the schedule with its entry editor, the stock keeper's search, address, order and scroll. Display test group `create`: 29 displays, passed in game. **Not drawn:** Create's client-side particles (the wand's end-rod particles and others), which are not recorded state |
| 4.2 The owner's mods (Create Addons, McpCraft) | Done | Hooks in the mods themselves (see "Stage 4.2 in detail"). **Create Addons:** its windows by stage 1, with extras putting back its Sort and quick-move buttons; the Electric Motor, Clockwork Dynamo, Geothermal Turbine, Splitter, Trade Bot and Trade Master screens claimed, with the server's power settings recorded; the jetpack gauge, villager goggles, encased lines seen through goggles and the container-picking line claimed. Display test group `createaddons`: 19 displays. **McpCraft:** the job board (each tab, the post draft), Job Storage, the job and factory prompts, the confirm over the board or a storage, the track markers and job beacon, and the markers' toggle line, all claimed; the board's JSON stored once in the recording. Display test group `mcpcraft`: 9 displays. Both passed in game. **Left out:** McpCraft's job board faces (world appearance from a message playback holds back, so boards look bare), its sync screen (a pause-menu screen), Create Addons' Juggernaut camera shake (not interface) |
| 4.3 Jade | Done | `compats/jade` (mod id `dailies_jade`), a jar of its own beside Jade 15.10.6: Jade's box recorded as Jade drew it (every element with its size, place, scale, fade, theme stored once, the breaking line) and drawn again by Jade's own code; a plugin's custom element keeps its place empty, logged once. Left out: the mod name Jade adds to item tooltips, its chat hints, the narrator, its config screens. Display test group `jade`: 4 displays, passed in game; the other compat groups pass with Jade loaded |
| 4.4a Create Aeronautics and its add-ons | Done | `compats/aeronautics` (mod id `dailies_aeronautics`). Simulated: the altitude sensor, modulating linked receiver, nameplate and contraption diagram screens claimed; the linked typewriter through stage 1 with extras (keys being set, the key list and scroll, a key's entry); the typewriter binding box, the readout while an assembler lever, steering wheel or throttle lever is held, and the physics staff's padlocks claimed. Simulated Coasters: the track placement row, the hint row and the Bezier handles claimed. Client-side action-bar lines of all six mods. Offroad: only the tyre line; Aeronautics and Create Propulsion: nothing of their own (Propulsion's outlines and value boxes go through the Create compat). **Left out:** Gyroscope Stabilizers' joystick screen and HUD, which the pack's jar never registers, so they never show; Coasters' curve highlight and its never-opened private-build notice. Display test group `aeronautics`: 13 displays, passed in game; the other compat groups pass with the add-ons loaded. Not in the test: the diagram, the staff's padlocks and the handles (they need a Sable craft or built track) |
| 4.4b Supplementaries and Amendments | Done | `compats/supplementaries` (`dailies_supplementaries`): the doormat, sign post, blackboard and speaker screens and Amendments' lectern book claimed; the present, trapped present, cannon and red merchant windows through stage 1 with extras, the notice board, pulley, sack, lunch basket and safe through stage 1 alone; the slime, quiver row, cannon controls and both mods' status lines; the cannon's trajectory and the Stasis slingshot's outline drawn into the world. 17 displays, and the placing check (a skull pile, skull candles, books, sticks, a blaze rod, gunpowder, pancakes, a wall lantern, a hanging pot, a ceiling banner: all in playback with their states and data after play and after a jump, the picture within 20/255 of the live one), passed in game |
| 4.4c Create's other add-ons | Done | `compats/createextras` (`dailies_createextras`): Create Deep Seas' sonar, PDA, implosion, engine throttle and console (its windows through stage 1); Create Connected's pulse generator; Create Encased's automatic clutch and recaser wheel (its ammo is sheets); Steam 'n' Rails' bogey menu, tag wheel and train switch; Create Jetpack's controls; Hypertubes' arrow, escape bar and outline; the add-ons' status lines. Left out: Tracks' render-tuning tool (writes a config). 12 displays, passed in game |
| 4.4d The rest of the pack | Done | `compats/extras` (`dailies_extras`): the compasses' lines while held; Sophisticated Backpacks' window claimed whole (upgrade tabs, scroll, search); Curios and Corpse through stage 1 with extras; VanillaBackport's nautilus inventory claimed. Left out at the owner's word: the Locator Bar (and VanillaBackport's own), Vista, Emotecraft and Pipez (out of the pack). Stage 1's stand-in player now stands where the recorder stood (PLAY-20). 7 displays, passed in game; all compat groups pass together, 125 displays |

Known limits of stage 1, for a compat or a later fix:
- other mods' listeners on window draw events still fire in playback;
- a mod window may write into the playback world's blocks as it is filled;
- the recipe book panel is never shown;
- a mod window already open when recording starts is left out;
- the creative inventory is not recorded (the owner's choice);
- the companion has no unit tests of its own (its routing is tested in the core, `CompatRoutesTest`);
- a letter read in playback is drawn only if the recorder's held letter matches the one recorded;
- a jump that lands inside a book's still stretch may show the world up to one tick early; voice arriving under the pause menu still lands on one tick, as before;
- only the game's exact book and sign screen classes are recorded: a mod's screen built on one is left out until a compat takes it.
- the Jade compat reads Jade's private fields by their 15.10.6 names, and swaps the recorder's Jade theme and settings in only while drawing; smooth progress and fade move in steps of recorded ticks.
- the Aeronautics compat reads Simulated 1.3.2's and Simulated Coasters 0.1.5's private fields by name; the diagram is rebuilt in playback with Veil framebuffers on the playback world's craft, untested in game.
- the template compat is not in the game test;
- the modded test now expects Post Rider loaded, from the pack or `dailies.modJars`.
- the Create compat reads some of Create's private fields by their 6.0.10 names: a rename in a Create update loses that part, logged once;
- Create hides the goggles' readout while a value box shows, so a recording does too.
- in playback the Create compat removes every outline Create works out from the playback camera, and draws only the recorded ones;
- the Create compat's status-line mixin keeps only action-bar lines sent from Create's or Catnip's code (found by a stack walk).
- in playback the Create compat briefly swaps some of the viewer's state while drawing and puts it back (the held item for the toolbox wheel's tip, the view's turn for the wrench wheel, Create's schematic name, Catnip's fade counter);
- the Create compat rebuilds the schedule's entry editor in playback through Create's private fields and methods.
- McpCraft's markers have no display entry: they need a railway planned on World View;
- found while testing 4.2 and fixed in core: a compat's screen opened on a snapshot's tick was held only by the snapshot, which play running into a segment skips, so it was never shown (CompatWrites, with CompatWritesTest).

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

**Left out on purpose:** Xaero's Minimap and World Map, AppleSkin and the Locator Bar (the owner does not want them in videos), Vista, Emotecraft and Pipez (the owner is taking them out of the pack), FPS
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

7. **Books, lecterns and signs (the owner's choice, 19 November).** The spec left these out by an earlier choice that
   was never the owner's; they are opened by using something in the world during play, so they count as play.
   - Reading a written book from the hand: which hand, the page shown, and the book's pages (stored once in the
     recording, as compats store large data), drawn with the game's own book screen.
   - A book on a lectern: the lectern window (a server window) with its page, drawn by stage 1's path or claimed if
     that cannot draw it right.
   - Writing in a book and quill: the pages as typed, the page, the cursor and selection, and the signing screen with
     its title, a few times a second while open, drawn read-only.
   - Editing a sign or a hanging sign: which sign (its place, wood and side), the lines as typed and the line being
     typed on, drawn with the game's own sign editing screen, read-only, sending nothing.
   - Shown under the owner's rule (Show HUD, first person riding the recorder, the "Open windows" look) like every
     window. Jumps, trim and combine as for every other recorded screen.
   - Built into Dailies, through the same compat API the mods use (a compat of Dailies' own), so snapshots and jumps
     work as for the rest.
   - Display entries in the `windows` group: a written book read (two pages turned), a lectern, a book and quill being
     written and then the signing screen, a sign and a hanging sign being edited.
   - Spec: REC-25 no longer leaves out books and signs (the creative inventory stays out), rows for each, TEST-8.
   - **Paused for a book (the owner's choice, 19 November).** In singleplayer the game pauses while a book is read or
     written, and stays paused: the owner wants vanilla's pause. But the recording keeps time while a recorded play
     screen that pauses the game is open: ticks go on being recorded at the usual rate with the world standing still,
     and the screen's state with them (pages turned, words typed), so in playback the world stands still and the book
     stays open for as long as it was shown. The pause menu and every other screen that is not a recorded play screen
     still stop the recording's clock, as today. On a server nothing pauses, so nothing changes there.

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

## Stage 4: the rest of the pack, by value (Compat, L) — Done

In order of how often they appear in a Create let's play:

1. Create: every machine setting first (the value settings board and value box, and the machine screens, so a viewer sees what speed, direction or amount was set), then the goggles overlay, then train schedules and the clipboard.
2. ComedyMonkey's Create Addons (its screens and overlays) and McpCraft (the job board, the prompts and job storage): the owner's own mods. These need no compat jar: the owner can add hooks to the mods themselves, so each implements the compat API directly as a soft dependency (found through `DailiesApi`, as McpCraft already does for playback), records exactly what its screens need, and sends any server-side facts from its own server code.
3. Jade.
4. Supplementaries, Create Deep Seas, the flight and ride overlays, the compasses.

Each takes the stage 3 shape: what is the least data that draws it again, does the server need to send anything, and
a small display test.

### Stage 4.4 in detail: the rest of the pack (Compat, three jars, built side by side)

Three compat jars, each a Gradle subproject built with Dailies like the Create compat, each part active only when its
mod is installed, each with a sweep of its mods' jars (screens, GUI layers, world drawing, action-bar lines) and a small
display test of its own group. Versions as in the owner's pack.

**4.4b, `compats/supplementaries` (`dailies_supplementaries`), group `supplementaries`:** Supplementaries 4.0.2 and
Amendments 2.2.7 (on Moonlight 3.8.3).
- Windows (stage 1, extras where needed): presents, notice board, pulley, cannon, red merchant, and any other.
- Client-only screens: blackboard, sign post, doormat, speaker, Amendments' lectern book editing and anything else.
- Overlays: the cannon's aiming, the slimed overlay, the selected-item overlays, and anything else the sweep finds.
- **Placed items (the owner's word):** a stack of skulls and Supplementaries' placeable items (books, bottles, jars and
  the rest of its list), placed as a player places them while recording, must be in playback at the same place with the
  same block state and block entity data, and drawn as live, after plain play and after a jump. A world check beside the
  display test, fixed in core if a snapshot does not describe them fully.

**4.4c, `compats/createextras` (`dailies_createextras`), group `createextras`:** Create's add-ons in the pack that the
Create compat does not cover: Create Deep Seas (`create_submarine` 3.3.0: boat engine, electrolyzer, sonar), Create
Connected 1.3.3 (sequenced pulse generator and others), Create Encased 1.10.1 (auto clutch and others), Tracks 1.0.1
(track tuning), Steam 'n' Rails 0.3.0-beta.2, Create Jetpack 5.2.1 (its flight HUD) and Create Hypertube 0.6.0 (its
ride and placement overlays). Their Catnip outlines, value boxes and goggle lines already go through the Create compat.

**4.4d, `compats/extras` (`dailies_extras`), group `extras`:** Explorer's Compass and Nature's Compass 3.4.0 (the search
result while held; their search lists are menus, not play). Vista, Emotecraft and Pipez are left out: the owner is
taking them out of the pack (19 November). Stage 1 is checked, with a
display entry each, for the windows of Sophisticated Backpacks 3.26.21, Curios 9.5.1, Corpse 1.1.22 and
VanillaBackport 1.2.2, with extras where a window loses client-side state.

**For all three:** recorded as drawn, never worked out again from the playback world; drawn under the owner's rule;
the lessons from Create's tests apply; each mod joins the modded test's mods if it is not there. Left out as not play:
config screens, JEI and Inventory Profiles Next panels, the minimap, AppleSkin and the Locator Bar (the owner's word).

### Stage 4.4a in detail: Create Aeronautics and its add-ons (Compat, M)

Taken before the rest of 4.4 at the owner's word (19 November). Aeronautics comes bundled as three mods, Aeronautics,
Simulated and Offroad (the bundle 1.3.2 in the pack, on Sable 2.0.6), and the pack adds Gyroscope Stabilizers, Create
Propulsion and Simulated Coasters. Their compat is a jar of its own, `compats/aeronautics` (mod id
`dailies_aeronautics`), built with Dailies like the Create compat, each part active only when its mod is installed.

From a scan of the jars (the compat's sweep confirms and completes it):
- **Simulated's screens:** the altitude sensor, the diagram, the entry modifier, the key editor, the linked typewriter,
  the modulating linked receiver and the nameplate. Each is checked: a server window goes through stage 1 (with extras
  for client-side state), a client-only screen is claimed and drawn read-only.
- **Simulated's overlays and messages:** the linked typewriter's binding HUD, and the messages its zipline, plunger
  launcher and tyres put above the hotbar. Its additions to Create's goggles and display link screen are already
  recorded by the Create compat, which records what was drawn.
- **Gyroscope Stabilizers:** the joystick HUD and the joystick screen.
- **Simulated Coasters:** the cart edit HUD, the rivet placement HUD, the smooth-track hint, and the Bezier handles and
  track placement preview drawn into the world (drawn again into the world as the Create compat draws its outlines).
- **Create Propulsion:** nothing drawn during play was found; the sweep confirms.
- **Aeronautics and Offroad:** no screens or overlays were found; the sweep confirms, including anything drawn into the
  world while building or flying.
- Left out as not play: config screens, Ponder, creative-tab changes, and Simulated Coasters' "private build blocked"
  screen if it is a notice rather than play (the sweep decides and says why).
- **A small display test** in an `aeronautics` group, one display per screen, overlay and tab that can be set up
  simply. Gyroscope Stabilizers, Propulsion and Simulated Coasters join the modded test's mods.
- The lessons from Create's tests apply (each mod's own conditions for drawing, survival player, yaw and pitch, block
  entities after `setblock`, block state names, a command that does not run is logged).

### Stage 4.3 in detail: Jade (Compat, M)

Jade is another person's mod, so its compat is a jar of its own, `compats/jade` (mod id `dailies_jade`), built with
Dailies like the Create compat, and loaded only when both Dailies and Jade are installed. Compiled against the Jade in
the owner's pack, 15.10.6 for NeoForge 1.21.1.

- **The tooltip box:** what the player looks at, as Jade drew it: the lines and every element in them (text, item
  icons, progress bars, fluid and energy gauges, armour and health rows, the mod name), the box's place, size, colours
  and theme, the block or entity icon, and its fade. Recorded from what Jade drew on the recorder's game, never worked
  out again from the playback world, since a server's data (a chest's contents, a furnace's progress) came from the
  server and the world may differ.
- **Jade's other play-time drawing:** found by a sweep of the jar (every GUI layer, world drawing and action-bar
  message), each covered or left out with the reason. Jade's config screens, its plugin list and its key bindings are
  not play.
- **Drawn again** with Jade's own drawing code where it can be handed the recorded elements, under the owner's rule
  (Show HUD, first person riding the recorder), as an overlay: it shows with the HUD, not under "Open windows".
- **Where Jade sits beside the other compats:** the Create compat records Create's goggles separately; Jade's box and
  Create's goggles can both show, and each records its own.
- **A small display test** in a `jade` group: looking at a chest with items in it, a furnace burning, a mob with
  health, and a Create machine, each checked drawn in first person and not in third person. Jade joins the modded
  test's mods.
- The lessons from Create's tests apply: check Jade's own conditions for showing the box (its config, the look
  distance, sneaking for details), turn the player by yaw and pitch, wait for block entities, log a command that does
  not run.

### Stage 4.2 in detail: ComedyMonkey's Create Addons and McpCraft (Owner's mods, M each)

Both are the owner's own mods, so, as Post Rider did, each carries its own hooks (a `client/dailies` package) instead of
a compat jar. Create Addons first, then McpCraft.

**ComedyMonkey's Create Addons** (`comedymonkeysaddons`):
- Screens: animal pen, auto harvester, chest upgrade, upgraded and large upgraded chests, fish tank, monster cage,
  pointed dripstone generator, wellspring (server-driven windows: stage 1 should draw them; extras only for client-side
  state such as a tab or a scroll); clockwork dynamo, electric motor, geothermal turbine, splitter, Trade Bot station and
  Trade Master station (each checked: a client-only screen is claimed, a server window gets extras if needed).
- Overlays: the jetpack HUD and the villager goggles, recorded as the recorder's game showed them.
- Its blocks' Create value boxes and goggle lines are already recorded by the Create compat when both are installed.
- A sweep of the mod for anything else drawn during play (every screen, GUI layer, world drawing and action-bar
  message), each listed as covered, stage 1, or left out with the reason.

**McpCraft** (`mcpcraft`):
- Screens: the job board, the job prompt, the factory prompt, job storage, and the confirm screen where it is shown from
  those during play.
- A sweep, as for Create Addons, for any other play-time interface (HUD, messages, world marks).
- Its playback check (`DailiesApi.isPlayback`, by reflection) keeps working. A screen that can't be opened in a test
  world without an outside AI service is said so and left out of the test, not faked.

**For both:**
- Soft dependency as Post Rider: the hook package loads only when Dailies is loaded, catching `LinkageError`; compiled
  against a copy of Dailies' API jar in the mod's `libs/` as `compileOnly`, never packed; no local paths in tracked
  files. Each still builds, passes its tests and runs without Dailies.
- The API jar has grown since Post Rider's copy (`overlaysShown`, `storeOnce`, both additive). The copies are taken from
  a fresh `gradlew apiJar`; Dailies' version is bumped before its next release, so jars of different contents never
  share a name.
- A small display test in each mod, one display per screen and overlay and one per tab of a screen with tabs, in Dailies'
  self test groups `createaddons` and `mcpcraft`. The modded test loads the hooked jars through `dailies.modJars`.
- Lessons from Create's tests: turn the player by yaw and pitch, not `tp … facing`; check each mod's own conditions for
  drawing, as a survival player may lack what a screen needs; wait for block entities after `setblock`; check block
  state names against the mod's code; a test command that does not run is logged.
- The owner's uncommitted work in either repository is never touched: hooks go on a branch in a worktree.

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
