package comedymonkey.momentscompanion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The events the server raises by itself: things a player's own game can't know, or can only guess at. Each goes to
 * the player it happened to and, where others would have seen it, to the players nearby.
 *
 * <pre>
 * boss_fight       a boss died: who fought it, how much damage each did, how long it took    (fighters and nearby)
 * close_call       survived a hit with almost no health left                                 (that player)
 * totem_saved      a totem of undying saved someone                                          (them and nearby)
 * big_fall         survived a long fall                                                      (that player)
 * explosion        an explosion destroyed blocks nearby, with what caused it                 (nearby)
 * lightning_strike struck by lightning                                                       (them and nearby)
 * animal_tamed     tamed an animal                                                           (that player)
 * animal_bred      bred two animals (once every two minutes per kind)                        (that player)
 * treasure_fished  fished up something that isn't a fish                                     (that player)
 * night_skipped    the night was slept through                                               (everyone in that world)
 * first_join       someone joined the server for the first time                              (everyone)
 * </pre>
 */
public final class Detectors {
	private Detectors() {}

	static final List<String> EVENTS = List.of("boss_fight", "close_call", "totem_saved", "big_fall", "explosion", "lightning_strike", "animal_tamed",
		"animal_bred", "treasure_fished", "night_skipped", "first_join");

	// ------------------------------------------------------------------ boss fights
	private static final class Fight {
		long started = System.currentTimeMillis(), last = started;
		final Map<UUID, Float> damage = new HashMap<>();
		final Map<UUID, String> names = new HashMap<>();
	}

	private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
	private static final long FIGHT_FORGET_MILLIS = 30 * 60_000;

	private static boolean boss(LivingEntity e) {
		return !(e instanceof ServerPlayer) && (e.getType().is(Tags.EntityTypes.BOSSES) || e.getMaxHealth() >= CompanionConfig.bossHealth());
	}

	private static void bossHurt(LivingEntity boss, DamageSource source, float amount) {
		if (!(source.getEntity() instanceof ServerPlayer p) || !real(p) || amount <= 0)
			return;
		long now = System.currentTimeMillis();
		FIGHTS.values().removeIf(f -> now - f.last > FIGHT_FORGET_MILLIS);
		Fight f = FIGHTS.computeIfAbsent(boss.getUUID(), k -> new Fight());
		f.last = now;
		f.damage.merge(p.getUUID(), amount, Float::sum);
		f.names.put(p.getUUID(), name(p));
	}

	private static void bossDied(LivingEntity boss, @Nullable DamageSource source) {
		Fight f = FIGHTS.remove(boss.getUUID());
		if (!CompanionConfig.enabled("boss_fight") || !(boss.level() instanceof ServerLevel level))
			return;
		String bossName = boss.getName().getString();
		JsonObject data = new JsonObject();
		data.addProperty("boss", id(boss));
		data.addProperty("boss_name", bossName);
		data.addProperty("max_health", round(boss.getMaxHealth()));
		Set<ServerPlayer> to = new LinkedHashSet<>(near(level, boss, CompanionConfig.nearbyRadius() * 2));
		String label = bossName + " was defeated";
		if (f != null && !f.damage.isEmpty()) {
			float total = 0;
			for (float d : f.damage.values())
				total += d;
			List<Map.Entry<UUID, Float>> order = new ArrayList<>(f.damage.entrySet());
			order.sort(Map.Entry.<UUID, Float>comparingByValue(Comparator.reverseOrder()));
			JsonArray fighters = new JsonArray();
			List<String> names = new ArrayList<>();
			for (Map.Entry<UUID, Float> e : order) {
				JsonObject o = new JsonObject();
				o.addProperty("name", f.names.get(e.getKey()));
				o.addProperty("damage", round(e.getValue()));
				o.addProperty("share", round(e.getValue() / total));
				fighters.add(o);
				names.add(f.names.get(e.getKey()));
				ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
				if (p != null)
					to.add(p);
			}
			data.add("fighters", fighters);
			data.addProperty("seconds", Math.round((System.currentTimeMillis() - f.started) / 1000.0));
			label = bossName + " was defeated by " + list(names) + " after " + data.get("seconds").getAsLong() + " seconds";
		}
		if (source != null && source.getEntity() instanceof ServerPlayer killer)
			data.addProperty("killer", name(killer));
		for (ServerPlayer p : to)
			MomentsCompanion.send(p, "boss_fight", label, data);
	}

	// ------------------------------------------------------------------ damage, death, falls
	private static final Map<UUID, Float> FALLS = new HashMap<>();

	@SubscribeEvent
	public static void onFall(LivingFallEvent e) {
		if (e.getEntity() instanceof ServerPlayer p && real(p)) {
			if (e.getDistance() >= CompanionConfig.bigFallDistance())
				FALLS.put(p.getUUID(), e.getDistance());
			else
				FALLS.remove(p.getUUID());
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onDamage(LivingDamageEvent.Post e) {
		LivingEntity hurt = e.getEntity();
		DamageSource source = e.getSource();
		if (hurt instanceof ServerPlayer p) {
			if (!real(p) || e.getNewDamage() <= 0)
				return;
			Float fell = source.is(DamageTypeTags.IS_FALL) ? FALLS.remove(p.getUUID()) : null;
			if (!p.isAlive() || p.getHealth() <= 0)
				return;
			if (fell != null && CompanionConfig.enabled("big_fall")) {
				JsonObject data = new JsonObject();
				data.addProperty("blocks", Math.round(fell));
				data.addProperty("damage", round(e.getNewDamage()));
				data.addProperty("health_left", round(p.getHealth()));
				MomentsCompanion.send(p, "big_fall", name(p) + " survived a " + Math.round(fell) + " block fall", data);
			}
			if (p.getHealth() <= CompanionConfig.closeCallHealth() && CompanionConfig.enabled("close_call") && cooled(p, "close_call", 30_000)) {
				JsonObject data = new JsonObject();
				data.addProperty("health_left", round(p.getHealth()));
				data.addProperty("damage", round(e.getNewDamage()));
				data.addProperty("cause", source.getMsgId());
				String by = "";
				if (source.getEntity() != null) {
					data.addProperty("attacker", id(source.getEntity()));
					data.addProperty("attacker_name", source.getEntity().getName().getString());
					by = " fighting " + source.getEntity().getName().getString();
				}
				MomentsCompanion.send(p, "close_call", name(p) + " survived with " + hearts(p.getHealth()) + " left" + by, data);
			}
			return;
		}
		if (boss(hurt)) {
			bossHurt(hurt, source, e.getNewDamage());
			// the dragon never "dies" as other mobs do: its last hit starts the death animation
			if (hurt instanceof EnderDragon dragon && dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING && FIGHTS.containsKey(dragon.getUUID()))
				bossDied(dragon, source);
		}
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onDeath(LivingDeathEvent e) {
		if (e.isCanceled())
			return;
		if (e.getEntity() instanceof ServerPlayer p)
			FALLS.remove(p.getUUID());
		else if (boss(e.getEntity()))
			bossDied(e.getEntity(), e.getSource());
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onTotem(LivingUseTotemEvent e) {
		if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || !real(p) || !CompanionConfig.enabled("totem_saved"))
			return;
		JsonObject data = new JsonObject();
		data.addProperty("player", name(p));
		data.addProperty("cause", e.getSource().getMsgId());
		String by = "";
		if (e.getSource().getEntity() != null) {
			data.addProperty("attacker", id(e.getSource().getEntity()));
			by = " from " + e.getSource().getEntity().getName().getString();
		}
		for (ServerPlayer to : near(p.serverLevel(), p, CompanionConfig.nearbyRadius()))
			MomentsCompanion.send(to, "totem_saved", "A totem of undying saved " + name(p) + by, data);
	}

	// ------------------------------------------------------------------ the world
	@SubscribeEvent
	public static void onExplosion(ExplosionEvent.Detonate e) {
		int blocks = e.getAffectedBlocks().size();
		if (!(e.getLevel() instanceof ServerLevel level) || blocks < CompanionConfig.explosionMinBlocks() || !CompanionConfig.enabled("explosion"))
			return;
		JsonObject data = new JsonObject();
		data.addProperty("blocks", blocks);
		Entity direct = e.getExplosion().getDirectSourceEntity();
		LivingEntity cause = e.getExplosion().getIndirectSourceEntity();
		String what = "An explosion";
		if (direct != null) {
			data.addProperty("source", id(direct));
			what = direct.getName().getString();
		}
		if (cause != null && cause != direct) {
			data.addProperty("caused_by", cause.getName().getString());
			what += " set off by " + cause.getName().getString();
		}
		var c = e.getExplosion().center();
		for (ServerPlayer p : level.players())
			if (real(p) && p.position().distanceToSqr(c) <= CompanionConfig.nearbyRadius() * CompanionConfig.nearbyRadius() && cooled(p, "explosion", 10_000))
				MomentsCompanion.send(p, "explosion", what + " destroyed " + blocks + " blocks", data);
	}

	@SubscribeEvent
	public static void onLightning(EntityStruckByLightningEvent e) {
		if (!(e.getEntity() instanceof ServerPlayer p) || !real(p) || !CompanionConfig.enabled("lightning_strike") || !cooled(p, "lightning_strike", 10_000))
			return;
		JsonObject data = new JsonObject();
		data.addProperty("player", name(p));
		for (ServerPlayer to : near(p.serverLevel(), p, CompanionConfig.nearbyRadius()))
			MomentsCompanion.send(to, "lightning_strike", name(p) + " was struck by lightning", data);
	}

	@SubscribeEvent
	public static void onSleep(SleepFinishedTimeEvent e) {
		if (!(e.getLevel() instanceof ServerLevel level) || !CompanionConfig.enabled("night_skipped"))
			return;
		JsonArray sleepers = new JsonArray();
		List<String> names = new ArrayList<>();
		for (ServerPlayer p : level.players())
			if (p.isSleeping()) {
				sleepers.add(name(p));
				names.add(name(p));
			}
		if (names.isEmpty())
			return;
		JsonObject data = new JsonObject();
		data.add("sleepers", sleepers);
		data.addProperty("players_in_world", level.players().size());
		for (ServerPlayer p : level.players())
			MomentsCompanion.send(p, "night_skipped", list(names) + " slept through the night", data);
	}

	// ------------------------------------------------------------------ animals and fishing
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onTame(AnimalTameEvent e) {
		if (e.isCanceled() || !(e.getTamer() instanceof ServerPlayer p) || !real(p) || !CompanionConfig.enabled("animal_tamed"))
			return;
		JsonObject data = new JsonObject();
		data.addProperty("animal", id(e.getAnimal()));
		MomentsCompanion.send(p, "animal_tamed", name(p) + " tamed " + article(e.getAnimal().getType().getDescription().getString()), data);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onBreed(BabyEntitySpawnEvent e) {
		if (e.isCanceled() || !(e.getCausedByPlayer() instanceof ServerPlayer p) || !real(p) || e.getChild() == null || !CompanionConfig.enabled("animal_bred"))
			return;
		String animal = id(e.getChild());
		if (!cooled(p, "animal_bred:" + animal, 120_000))
			return; // a breeding session is one moment, not one per calf
		JsonObject data = new JsonObject();
		data.addProperty("animal", animal);
		MomentsCompanion.send(p, "animal_bred", name(p) + " bred " + e.getChild().getType().getDescription().getString().toLowerCase() + "s", data);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onFished(ItemFishedEvent e) {
		if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || !real(p) || !CompanionConfig.enabled("treasure_fished"))
			return;
		JsonArray items = new JsonArray();
		List<String> names = new ArrayList<>();
		for (ItemStack stack : e.getDrops())
			if (!stack.isEmpty() && !stack.is(ItemTags.FISHES)) {
				items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
				names.add(article(stack.getHoverName().getString()));
			}
		if (names.isEmpty())
			return;
		JsonObject data = new JsonObject();
		data.add("items", items);
		MomentsCompanion.send(p, "treasure_fished", name(p) + " fished up " + list(names), data);
	}

	// ------------------------------------------------------------------ players
	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
		if (!(e.getEntity() instanceof ServerPlayer p) || !real(p) || !CompanionConfig.enabled("first_join"))
			return;
		if (p.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) > 0 || p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) > 0)
			return;
		JsonObject data = new JsonObject();
		data.addProperty("player", name(p));
		MomentsCompanion.sendAll(p.server, "first_join", name(p) + " joined for the first time", data);
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
		UUID id = e.getEntity().getUUID();
		FALLS.remove(id);
		COOLDOWNS.keySet().removeIf(k -> k.startsWith(id.toString()));
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent e) {
		FIGHTS.clear();
		FALLS.clear();
		COOLDOWNS.clear();
	}

	// ------------------------------------------------------------------ helpers
	private static final Map<String, Long> COOLDOWNS = new HashMap<>();

	/** True at most once per millis for this player and key. */
	private static boolean cooled(ServerPlayer p, String key, long millis) {
		long now = System.currentTimeMillis();
		String k = p.getUUID() + "|" + key;
		Long last = COOLDOWNS.get(k);
		if (last != null && now - last < millis)
			return false;
		COOLDOWNS.put(k, now);
		return true;
	}

	/** A person, not a machine acting as one (Create's deployers and the like). */
	private static boolean real(ServerPlayer p) {
		return !(p instanceof FakePlayer) && p.connection != null;
	}

	/** The players within radius blocks of an entity, itself included when it is a player. */
	private static List<ServerPlayer> near(ServerLevel level, Entity around, double radius) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players())
			if (real(p) && p.distanceToSqr(around) <= radius * radius)
				out.add(p);
		return out;
	}

	private static String name(ServerPlayer p) {
		return p.getGameProfile().getName();
	}

	private static String id(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	private static String hearts(float health) {
		double h = Math.round(health) / 2.0;
		String n = h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h);
		return n + (h == 1 ? " heart" : " hearts");
	}

	private static String article(String thing) {
		return (thing.isEmpty() || "aeiouAEIOU".indexOf(thing.charAt(0)) < 0 ? "a " : "an ") + thing.toLowerCase();
	}

	private static String list(List<String> names) {
		if (names.size() <= 1)
			return names.isEmpty() ? "" : names.get(0);
		return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
	}
}
