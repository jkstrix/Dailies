# Dailies Companion

A small server-side mod for NeoForge 1.21.1. It tells a player's game that something worth remembering has just
happened to them, so that Dailies, running in that player's game, can write it into its moments log with the time and
the place.

It is optional on both sides:

- A player without Dailies is sent nothing and joins as usual.
- A server without the companion works like any other; Dailies simply has fewer things to log.

Mod id `dailies_companion`. Licence MIT.

## Install

Put `dailies-companion-1.21.1-<version>.jar` in the server's `mods` folder. Players need nothing extra: Dailies
registers the companion's channel itself. In singleplayer, put the jar in the game's `mods` folder beside Dailies.

## Build

```
gradlew build
```

The jar is written to `build/libs/`. `gradlew runServer` starts a bare dedicated server with only this mod, in
`run-server/`, for trying it against a client that does not have it.

## The `/moment` command

For operators, command blocks, functions and datapacks (permission level 2).

```
/moment send <players> <type> <label...>
/moment data <players> <type> <{nbt}> <label...>
```

- `players` is a player selector, such as `@a` or a name.
- `type` is one word naming the kind of event, in lower case: `quest_completed`, `town_founded`.
- `label` is the rest of the line, as a person would say it: `Founded the town of Brasswick`.
- `data` is an NBT compound holding anything else worth keeping. It arrives in the moment as JSON.

The reply says how many of the players were listening.

```
/moment send @a town_founded Founded the town of Brasswick
/moment data @p quest_completed {quest:"brass_age",reward:12} Finished the Brass Age
```

## Events the server raises by itself

| Type | When | Who is told |
|---|---|---|
| `boss_fight` | A boss dies: a mob in the `c:bosses` tag, or with at least `bossHealth` maximum health. | Everyone who fought it, each with their share of the damage, and players within twice `nearbyRadius`. |
| `close_call` | A player survives a hit with `closeCallHealth` health or less. At most once every 30 seconds. | That player. |
| `totem_saved` | A totem of undying saves a player. | Players within `nearbyRadius`, the saved player among them. |
| `big_fall` | A player survives a fall of at least `bigFallDistance` blocks. | That player. |
| `explosion` | An explosion destroys at least `explosionMinBlocks` blocks. | Players within `nearbyRadius` of it. |
| `lightning_strike` | Lightning strikes a player. | Players within `nearbyRadius`, the struck player among them. |
| `animal_tamed` | A player tames an animal. | That player. |
| `animal_bred` | A player breeds two animals. | That player. |
| `treasure_fished` | A player fishes up treasure. | That player. |
| `night_skipped` | The night is slept through. | Everyone in that world. |
| `first_join` | A player joins the server for the first time. | Everyone online. |

## Settings

`config/dailies_companion-common.toml`:

| Setting | Default | Meaning |
|---|---|---|
| `disabledEvents` | empty | Types from the table above that the server should not raise, for everyone. Players can also ignore types on their own side, in Dailies. |
| `closeCallHealth` | 4.0 | `close_call`: health at or below which a survived hit counts (2 is one heart). |
| `bigFallDistance` | 15.0 | `big_fall`: the shortest fall, in blocks. |
| `bossHealth` | 150.0 | `boss_fight`: the maximum health at which a mob counts as a boss. |
| `explosionMinBlocks` | 10 | `explosion`: the fewest blocks destroyed. |
| `nearbyRadius` | 48.0 | How close, in blocks, other players have to be to hear about something that happened to someone else. |

## For other server mods

```java
DailiesCompanion.send(player, "town_founded", "Founded the town of Brasswick", data);      // one player
DailiesCompanion.sendNear(level, position, 48.0, "train_departed", "The 9:15 left", null); // everyone close by
DailiesCompanion.sendAll(server, "season_changed", "Winter has come", null);               // everyone online
DailiesCompanion.listening(player);                                                        // is their game listening?
```

`data` is a Gson `JsonObject` or null. Each call returns whether, or how many of, the players were listening.

## The message

One optional NeoForge channel, `dailies_companion:event`, version `1`, server to client, carrying three strings: the
type (up to 64 characters), the label (up to 256) and a JSON object (up to 32,767). When the companion is also in the
player's own game, it posts each message it receives on the NeoForge event bus as
`comedymonkey.dailiescompanion.MomentClientEvent`.

The mod was called Moments Companion (`moments_companion`) before version 0.2. A server and its players must be on the
same side of the rename: Dailies listens on the new channel only.
