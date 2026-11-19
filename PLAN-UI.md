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
menu, selection wheels, and title, login, setup and debug screens.

Recording works from an allow list, not a block list: a screen is recorded only when Dailies (stage 1) or a compat
(stages 2 to 4) says it is a play screen. The deny list in the decisions below applies only within that allow list,
for server-driven windows that are play screens but draw wrongly in playback.

Sizes as in `PLAN.md`: **S** under an hour, **M** a session, **L** several sessions.

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
Chat's group and setup screens (its icons are already a scene look), Better Advancements, Emotecraft's emote wheel
and the compasses' biome lists (menus, not play screens), statistics, the recipe book panel, and every config screen.

---

## Stage 1: standard windows in the core and the companion (Spec + core, L)

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
   - Scripted steps: open a chest, a furnace and a villager's trades while recording. In a first-person export with
     the HUD on, each window must match the live game's screen (as the bestiary compares light). In third person, or
     with the HUD off, it must be absent. A jump into an open window, and a trim cutting through one, must show it.

## Stage 2: the compat API and a template (Spec + core + companion, M)

1. **Client API (`comedymonkey.dailies.api.compat`).** A compat registers:
   - an id and a version;
   - the play screens and overlays it claims;
   - how it records them (its own extension records, as little data as will draw them again);
   - how it draws them in playback;
   - what it holds for a snapshot;
   - a scripted test step.

   Its records carry its version. A recording lists the compats that wrote into it; playback without one plays on
   without that layer and says so once.
2. **Server API.** On the companion's plugin API from stage 1: a compat's server half sends facts only the server
   knows. Players who opted out of voice capture, and the plan's privacy rules, apply to compat data as to any other.
3. **A template compat project:** Gradle, the client and server halves, one example screen and one overlay, and a test
   step. It is copied to start each compat.

## Stage 3: Post Rider as the first compat (Compat, M)

Post Rider has one window and two screens, which makes it a good first test.

1. **Mailbox:** a server-driven window, so stage 1 should already show it. The compat checks that and fixes what stage 1
   cannot.
2. **Reading a letter:** the text is in the letter item the player holds, which the recording already has. The compat
   records which letter was opened, and when, and draws Post Rider's own reading screen from it.
3. **Writing a letter:** the compat records the text as it changes, a few times a second while the screen is open, and
   draws the writing screen with the text as it stood at each tick.
4. **Server half:** none is expected: the client already has the mailbox contents and the letters. If delivery or
   mail state turns out to be server-only, the companion API carries it.
5. **Test:** Post Rider joins the modded test's mods. A scripted step opens a mailbox, reads a letter and writes one
   while recording, and checks a first-person export against the live screens.

## Stage 4: the rest of the pack, by value (Compat, L)

In order of how often they appear in a Create let's play:

1. Create: every machine setting first (the value settings board and value box, and the machine screens, so a viewer sees what speed, direction or amount was set), then the goggles overlay, then train schedules and the clipboard.
2. ComedyMonkey's Create Addons (its screens and overlays) and McpCraft (the job board, the prompts and job storage): the owner's own mods. These need no compat jar: the owner can add hooks to the mods themselves, so each implements the compat API directly as a soft dependency (found through `DailiesApi`, as McpCraft already does for playback), records exactly what its screens need, and sends any server-side facts from its own server code.
3. Jade.
4. Supplementaries, Create Deep Seas, the flight and ride overlays, the compasses.

Each takes the stage 3 shape: what is the least data that draws it again, does the server need to send anything, and
a test step.

---

## Decisions (made 19 November 2026)

| Decision | Chosen |
|---|---|
| Where compat jars live | Subprojects in this repository: one build, built and tested together with Dailies and the companion |
| Stage 1 for modded windows | Every server-driven window is tried generically; one that misdraws goes on a deny list, or gets a compat |
| The pointer | The recorded mouse pointer and the carried item are drawn over the window |
| Left out | Xaero's Minimap and World Map, AppleSkin |
| The owner's mods (ComedyMonkey's Create Addons, McpCraft) | Hooks written into the mods themselves where that is easier: they implement the compat API directly instead of a separate compat jar |
