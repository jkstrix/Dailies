# Dailies: plan for effects and music in playback

A recording plays back without some of what the player saw. Potion swirls can be missing on mobs and players. The
first person view lacks darkness, nausea, the portal wobble and the powder-snow frost. And the game's own background
music plays over playback. This plan makes playback and exports show the effects, keeps the music out, and adds a
test for each.

**The owner's rules (22 November 2026):**
- Both versions, `mc/1.21.1` and `main` (26.3), with a test for each effect.
- No added lag for any player. Every test also measures frame cost (idle, recording, playback).
- **Shown as the game shows them.** First-person overlays and screen effects go with the scene's **Show HUD** look:
  - With it on (someone is making a let's play), they show through the recorder's eyes just as the player saw them:
    the frost, the carved pumpkin, darkness, nausea, the portal wobble and the rest.
  - With it off (a cinematic picture), none of them show, as F1 hides the overlays in the game.
  - They never show in third person or with the camera flying free.
- Potion swirls on mobs and players belong to the world, and always show, in every view.
- **Fill every gap:** each first-person overlay or effect the game draws from the player's own state is covered, not
  only the four named. Step 0 lists them, and step 2.5 adds the rest.

**How first person works now:** in playback the game's camera entity is the recorded player, a stand-in player
entity. `minecraft.player` is the viewer's own, an invisible spectator. Most of the game's first-person effects read
`minecraft.player`, so they show the viewer's state (none), not the recorder's.

Sizes: **S** under an hour, **M** a session, **L** several sessions.

## Progress (22 November 2026, on 26.3; 1.21.1 to follow)

| Step | Status | Notes |
|---|---|---|
| 0 What is recorded already | Done | Missing and now recorded: the recorder's portal intensity and being on fire (the server never tells the burning player's own game), in a `dailies:own_state` record and in snapshots. Snapshots did not carry the recorder's potion effects, so a jump lost them; they do now |
| 1 Potion particles | Done | They already came through playback; the test counts them around the husk and the recorder in first and third person, and none after the effects wear off |
| 1.5 Invisibility, armour and trims | Done | Invisible entities are judged by the recorder, not the spectator viewer (`InvisibleMixin`); armour and its trims are still drawn on them; seen again when it wears off |
| 2.1 Frost (powder snow) | Done | `CameraOverlaysMixin` |
| 2.2 Darkness | Done | Lightmap (`LightmapEyesMixin`), fog only through their eyes (`EffectFogMixin`) |
| 2.3 Nausea | Done | Warp intensity, fade and spin, as the game keeps them (`RecorderEyes`) |
| 2.4 Portal wobble | Done | Recorded; warp and purple overlay |
| 2.5 The other first-person overlays | Done | Carved pumpkin and worn overlays (with a **Pumpkin overlay** look, a checkbox of its own), spyglass, fire, water, a block in the face, night vision and underwater sight, the sleep fade, a totem going off. The vignette was already drawn from the camera |
| 3 No music in playback | Done | `MusicMixin` |
| 4 Spec, tests, both versions | 26.3 done | Test group `effects` passes on 26.3. Port to 1.21.1 next |

## 0. What is recorded already (S)

Check, in a recording made with the effects on, what each step can build on:
- whether the recorder's own effect packets (`ClientboundUpdateMobEffectPacket`, `ClientboundRemoveMobEffectPacket`) reach the stand-in player in playback, so it holds the recorder's effects;
- the frozen ticks and the effect-particle list in each entity's synced data (both are synced to every client, so they should already be in recordings);
- what is missing outright: the portal intensity, which only the recorder's own game works out.

The result decides whether steps 2.2 to 2.5 need new recorded state. Only the portal wobble is expected to.

Also list every first-person overlay and screen effect both versions draw from `minecraft.player`. Known so far: the
vignette, the carved pumpkin (and any worn item's camera overlay), the spyglass scope, the frost, the portal's purple
overlay, fire, under water, inside a block, and the darkness and warp of 2.2 to 2.4. Check which already show through
the recorder's eyes and which do not. The ones that do not are step 2.5.

## 1. Potion particles on mobs and players, first and third person (M)

The game spawns these swirls on the client, in each living entity's tick, from the effect particles the server syncs
in its entity data. So nothing new should need recording; playback has to let them through. Today only jumps and
stretch previews drop particles (`ParticleEngineMixin`).

- Find where they go missing: whether the stand-in players and replayed mobs run that part of their tick in playback,
  whether the recorder's own particles are kept off the camera in first person, and whether exports draw them.
- The recorder's own swirls show in first person as they do in play.
- **Test** (`effects` group): record a mob given an effect (say a zombie with speed) and the recorder with one
  (regeneration). In playback, count the effect particles spawned near each one, in first person and in third person.
  Export a still in each view, and check the particles are in it.

## 1.5 Invisibility, armour and trims (S)

Added by the owner (22 November): entities with invisibility are invisible in playback, as they were to the recorder, and
are seen again when it wears off; their armour, trims and all, is still drawn on them, as in the game. The viewer is a
spectator, who would see invisible entities as ghosts, so they are judged by the recorder instead.

**Test:** a husk in gold-trimmed iron armour made invisible for a few seconds, and the recorder invisible in a trimmed
chestplate: invisible to the viewer, all armour drawn with its trims, and seen again once it wears off.

## 2. First-person screen effects from the recorder (M each, 2.4 is L)

Each is shown only through the recorder's eyes, with the scene's Show HUD look on, as the game shows it in play. It is
not shown in third person, with the camera flying free, or with Show HUD off. Each read that uses `minecraft.player`
uses the camera entity instead, while a recording is open and the view is through someone's eyes.

### 2.1 Frost from powder snow

The frost overlay is drawn from `minecraft.player.getTicksFrozen()`: in 26.3 by `Hud`, in 1.21.1 by
`Gui.renderCameraOverlays`. Frozen ticks are synced entity data, so the recorder's are already in the recording;
the edge steps' census already compares them (`frozen=`). The overlay reads the camera entity's instead. It is hidden
with Show HUD off, as F1 hides it in the game.

**Test:** the edge step already sinks the recorder into powder snow and freezes them. In first person, while frozen,
the frost overlay is drawn with the recorded frozen amount. It is absent in third person.

### 2.2 Darkness

Two halves:
- The fog already follows the camera entity's effects (`FogRenderer`, `DarknessFogEnvironment`).
- The pulsing dark of the lightmap reads `minecraft.player`'s darkness blend: `LightmapRenderStateExtractor` in 26.3,
  `LightTexture` in 1.21.1. It reads the camera entity's instead.

This needs step 0 to confirm the stand-in player holds the recorder's effects.

**Test:** the recorder is given darkness near a light source. In first person, the lightmap's darkness factor follows
the recorded effect, and a still is darker than the same tick in third person.

### 2.3 Nausea

The screen warp is the larger of the nausea and portal intensities, which the game keeps on `LocalPlayer`. The stand-in
player has neither. The nausea intensity is worked out from the effect, the same way the game does, with the same fade
in and out. It is put into the frame's player render state in 26.3, or the value `GameRenderer` reads in 1.21.1.

**Test:** the recorder is given nausea. In first person the warp intensity rises, holds and fades as recorded, and a
still shows the warp. Third person shows none.

### 2.4 The portal wobble

The portal intensity is the recorder's own game's state: how long they have stood in a nether portal. Nothing sends it
to anyone else. So it is recorded as the recorder's own state:
- a small record, written only when it changes;
- in snapshots, so jumps and trims keep it.

Playback feeds it into the same warp as 2.3. That is a recording-format change. No backwards compatibility is needed:
older recordings simply show no wobble. The portal's purple overlay is drawn from the same value.

**Test:** the recorder walks into a nether portal, waits, and steps out before it takes them. In first person, the
wobble's intensity and the purple overlay match the live game's tick by tick, a few ticks apart allowed. Third person
and Show HUD off show neither.

### 2.5 The other first-person overlays (M)

Every overlay step 0 finds missing is drawn from the recorder in the same way: the carved pumpkin, the spyglass scope,
fire, under water, inside a block, the vignette, and any others. Most read state already in the recording (what is
worn, being on fire, the eyes in water or in a block). Anything that is not gets a small recorded value, like the
portal's.

**Test:** one display for each, staged in the `effects` group's world. Each is drawn in first person with Show HUD on,
and absent in third person and with Show HUD off.

## 3. No music in playback (S)

The game's own background music (the `MusicManager`) picks music for the playback world as if it were being played.
While a recording is open or exporting, it plays nothing, and anything already playing stops when a recording opens.
Music comes back after closing, as the game chooses. Sounds of the recording itself stay: jukeboxes, note blocks and
everything else heard in the world.

**Test:** with a recording open, and through an export, the music manager plays nothing for the length of the step
(checked every second), and the export's sound track holds no music track. After closing, the game is free to play
music again.

## 4. Spec, tests and both versions (M)

- Spec rows under PLAY (effects and music) and EXP (the same in exports).
- A test group `effects` with the displays above, and the music check. They are staged in a flat world of their own,
  and recorded once and reused as a fixture, as the edge steps are.
- Built on `mc/1.21.1` first, then merged forward to `main` and adapted to 26.3:
  - `Hud` in 26.3 for `Gui`;
  - the player render state in 26.3 for direct reads;
  - `LightmapRenderStateExtractor` in 26.3 for `LightTexture`.
- Each version's tests pass before its PR.

## Frame cost

Each read changes source, not amount: one entity in place of another, once a frame. The portal record is a float, and
only when it changes. The tests compare frame times with and without the effects. A cost that shows is fixed before
merging, not accepted.

## Decisions

1. The frost overlay, and every first-person overlay, shows as the game shows it in play, and goes with Show HUD as F1
   does.
2. Every gap is filled: each first-person overlay or effect drawn from the player's own state is covered (step 2.5).
3. No separate look for the screen effects. In first person only: with Show HUD on (a let's play), overlays and effects
   show; with it off, none do. In third person or with the camera flying free they never show, whatever the HUD.
4. A **Pumpkin overlay** look, with a checkbox in the looks, turns the carved pumpkin's view (and other worn items'
   overlays) off; on by default.
