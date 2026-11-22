package comedymonkey.dailiescompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Craft and train moments, from the server, which knows for certain what happened (Dailies plan 2.1):
 *
 * <pre>
 * craft_assembled  Sable made a new physics craft ("sub-level")           (whoever assembled it, and players nearby)
 * craft_lost       a craft was destroyed, split, or lost over half its    (players nearby)
 *                  mass; the cause where it is known
 * train_departed   a Create train with the player aboard left a station   (that player, once per train)
 * train_arrived    that train reached its next station                    (that player)
 * </pre>
 *
 * The craft are followed in {@link SableCraft} and the trains in {@link CreateTrains}, which call Sable 2.0.6 and Create
 * 6.0.10 directly. Each is loaded only when its mod is, so the companion runs as before on a server without either; this
 * class names neither in any signature. What is here is shared: what players did just before (assemblies, explosions).
 */
final class CraftEvents {
	private CraftEvents() {}

	static final List<String> EVENTS = List.of("craft_assembled", "craft_lost", "train_departed", "train_arrived");

	/** A craft appearing this soon after a player assembled something near it is theirs */
	private static final long ASSEMBLY_WINDOW_MILLIS = 5_000;
	/** A craft removed this soon after an explosion close to it was blown up */
	private static final long EXPLOSION_WINDOW_MILLIS = 3_000;

	/** Whether Sable's craft and Create's trains are followed: each needs its mod */
	private static boolean sable = false;
	private static boolean create = false;

	// ------------------------------------------------------------------ setting up
	static void init() {
		ModList mods = ModList.get();
		create = mods.isLoaded("create");
		if (mods.isLoaded("sable")) {
			try {
				SableCraft.init();
				sable = true;
			} catch (Throwable t) {
				partFailed("Sable", "2.0.6", "Sable's craft could not be watched", "craft_assembled and craft_lost", t);
			}
		}
		NeoForge.EVENT_BUS.addListener((CommandEvent e) -> onCommand(e));
		NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> onUse(e));
		NeoForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> onExplosion(e));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
			if (sable)
				SableCraft.attachAll(e.getServer());
		});
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> forgetAll());
		if (create)
			NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> CreateTrains.tick(e.getServer()));
	}

	/**
	 * One line in the log when a part stops, naming the real cause. Only a linkage error (a class, method or field of the
	 * mod missing or changed) points at a different version of the mod than the one the companion was built against.
	 */
	static void partFailed(String mod, String builtAgainst, String what, String events, Throwable t) {
		String hint = t instanceof LinkageError
				? " " + mod + " looks different from the " + builtAgainst + " the companion was built against."
				: "";
		org.slf4j.LoggerFactory.getLogger("Dailies Companion").error("Dailies Companion: {}; {} are off. Cause: {}.{}", what, events, t, hint, t);
	}

	// ------------------------------------------------------------------ what players did just before
	record Action(UUID player, Vec3 where, long time) {}

	private static final List<Action> ASSEMBLIES = new ArrayList<>();
	private record Blast(ServerLevel level, Vec3 where, double radius, long time) {}
	private static final List<Blast> BLASTS = new ArrayList<>();

	private static void onCommand(CommandEvent e) {
		String command = e.getParseResults().getReader().getString().trim();
		if (command.startsWith("/"))
			command = command.substring(1);
		String[] words = command.split("\\s+");
		if (words.length < 2 || !words[0].equalsIgnoreCase("sable") || !words[1].equalsIgnoreCase("assemble"))
			return;
		Entity source = e.getParseResults().getContext().getSource().getEntity();
		Vec3 where = e.getParseResults().getContext().getSource().getPosition();
		remember(new Action(source instanceof ServerPlayer p && real(p) ? p.getUUID() : null, where, System.currentTimeMillis()));
	}

	private static void onUse(PlayerInteractEvent.RightClickBlock e) {
		if (!(e.getEntity() instanceof ServerPlayer p) || !real(p))
			return;
		String block = BuiltInRegistries.BLOCK.getKey(e.getLevel().getBlockState(e.getPos()).getBlock()).getPath();
		if (block.contains("assembler"))
			remember(new Action(p.getUUID(), Vec3.atCenterOf(e.getPos()), System.currentTimeMillis()));
	}

	private static synchronized void remember(Action action) {
		long now = System.currentTimeMillis();
		ASSEMBLIES.removeIf(a -> now - a.time() > ASSEMBLY_WINDOW_MILLIS * 2);
		ASSEMBLIES.add(action);
	}

	private static void onExplosion(ExplosionEvent.Detonate e) {
		if (e.getLevel() instanceof ServerLevel level) {
			long now = System.currentTimeMillis();
			synchronized (CraftEvents.class) {
				BLASTS.removeIf(b -> now - b.time() > EXPLOSION_WINDOW_MILLIS * 2);
				BLASTS.add(new Blast(level, e.getExplosion().center(), e.getExplosion().radius(), now));
			}
		}
	}

	/** The latest assembly near a place in the last five seconds, or null */
	static synchronized @Nullable Action assemblyNear(Vec3 where, double radius) {
		long now = System.currentTimeMillis();
		Action best = null;
		for (Action a : ASSEMBLIES)
			if (now - a.time() <= ASSEMBLY_WINDOW_MILLIS && a.where().distanceToSqr(where) <= radius * radius && (best == null || a.time() > best.time()))
				best = a;
		return best;
	}

	static synchronized void forgetAssembly(Action action) {
		ASSEMBLIES.remove(action);
	}

	static synchronized boolean blastNear(ServerLevel level, AABB bounds) {
		long now = System.currentTimeMillis();
		for (Blast b : BLASTS)
			if (b.level() == level && now - b.time() <= EXPLOSION_WINDOW_MILLIS && bounds.inflate(b.radius() + 2).contains(b.where()))
				return true;
		return false;
	}

	// ------------------------------------------------------------------ helpers
	private static void forgetAll() {
		if (sable)
			SableCraft.forgetAll();
		if (create)
			CreateTrains.forgetAll();
		synchronized (CraftEvents.class) {
			ASSEMBLIES.clear();
			BLASTS.clear();
		}
	}

	static boolean real(ServerPlayer p) {
		return !(p instanceof FakePlayer) && p.connection != null;
	}

	static List<ServerPlayer> near(ServerLevel level, AABB bounds, double radius) {
		List<ServerPlayer> out = new ArrayList<>();
		AABB around = bounds.inflate(radius);
		for (ServerPlayer p : level.players())
			if (real(p) && around.contains(p.position()))
				out.add(p);
		return out;
	}

	static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
