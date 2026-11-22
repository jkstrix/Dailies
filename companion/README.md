# Dailies Companion

A small server-side mod for NeoForge 26.3 (Minecraft 26.3). It tells a player's game that something worth remembering has just
happened to them, so that Dailies, running in that player's game, can write it into its moments log with the time and
the place.

It is for dedicated servers. Singleplayer needs nothing extra: Dailies carries a copy of the companion inside its own
jar (jar-in-jar), which runs in the game's own world, LAN included.

It is optional on both sides:

- A player without Dailies is sent nothing and joins as usual.
- A server without the companion works like any other; Dailies simply has fewer things to log.

Mod id `dailies_companion`. Licence MIT.

## Install

Put `dailies-companion-26.3-<version>.jar` in the server's `mods` folder. Players need nothing extra beside Dailies.
In singleplayer there is nothing to install: Dailies has the companion inside. A copy of the companion left in a
player's own `mods` folder does no harm; NeoForge loads the newer of the two.

## Build

```
gradlew build
```

The jar is written to `build/libs/`. Dailies' own build includes this one and packs the same jar into the Dailies jar. `gradlew runServer` starts a bare dedicated server with only this mod, in
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

### Craft and trains

The craft and train events (`craft_assembled`, `craft_lost`, `train_departed`, `train_arrived`) are not available on
Minecraft 26.3. They come from Sable's physics craft and Create's trains, and neither mod has a release for 26.3 yet, so
this build of the companion leaves them out. They come back once Sable and Create release for 26.3. The 1.21.1 build of
the companion still raises them.

## Settings

`config/dailies_companion-local.toml`:

| Setting | Default | Meaning |
|---|---|---|
| `disabledEvents` | empty | Types from the table above that the server should not raise, for everyone. Players can also ignore types on their own side, in Dailies. |
| `disabledPlugins` | empty | Server plugins (by id) that send nothing to anyone: left out of the list of plugins, answered as if the server had none, and what they push dropped. |
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

## Server plugins

For facts only the server knows, which a compat on the player's side records and draws again: the server half of a
compat registers a plugin with an id and a version, and pushes data to players. Dailies routes what arrives by the id
to the compat's client half and keeps it in the recording.

```java
CompanionPlugins.register(new CompanionPlugin() {
    public String id() { return "postrider:mail"; }
    public int version() { return 1; }
    // What this plugin has for a player, when their game asks (a client half asks as it starts); null for nothing
    public byte[] describe(ServerPlayer player, int clientVersion) { return mailFor(player); }
});
CompanionPlugins.push(player, "postrider:mail", bytes);  // one player; false when their game is not listening
CompanionPlugins.pushAll(server, "postrider:mail", bytes); // everyone online
```

The data is the plugin's own format, up to 256 KiB. One plugin is built in, `dailies_companion:plugins`: the list of
the server's plugins and their versions, which Dailies asks for as it joins, so a client half knows whether its
server half is there.

What a plugin sends is kept in the player's recordings and can end up in a video, so send each player only facts
about their own play, or what they could see for themselves in the world: never another player's inventory, mail,
position or messages that were not meant for them. `disabledPlugins` switches any plugin off for everyone. A compat's
client half is written with Dailies' compat API; `compats/template` in the Dailies repository has both halves, to copy.

## The messages

One optional NeoForge channel, `dailies_companion:event`, version `1`, server to client, carrying three strings: the
type (up to 64 characters), the label (up to 256) and a JSON object (up to 32,767). When the companion is also in the
player's own game, it posts each message it receives on the NeoForge event bus as
`comedymonkey.dailiescompanion.MomentClientEvent`.

The plugins share a second, `dailies_companion:compat`, version `1`, both ways: a varint kind (0 data, 1 ask), the
plugin's id (up to 128 characters), a varint version (the plugin's, 0 when the server has no such plugin; or the
asking client half's) and a byte string. When the companion is also in the player's own game, it posts each plugin's
data as `comedymonkey.dailiescompanion.CompatClientEvent`.
