package comedymonkey.dailiescompanion;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
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
 * Nothing here is compiled against Sable or Create. Every class is found by name and every method by name, once, so the
 * companion loads and runs as before on a server without either; a lookup that fails switches that part off with one
 * line in the log. Names are those of Sable 2.0.6 and Create 6.0.10.
 */
final class CraftEvents {
	private CraftEvents() {}

	static final List<String> EVENTS = List.of("craft_assembled", "craft_lost", "train_departed", "train_arrived");

	/** A craft appearing this soon after a player assembled something near it is theirs */
	private static final long ASSEMBLY_WINDOW_MILLIS = 5_000;
	/** A craft removed this soon after an explosion close to it was blown up */
	private static final long EXPLOSION_WINDOW_MILLIS = 3_000;
	/** Mass is looked at once a second */
	private static final int MASS_EVERY_TICKS = 20;

	private static boolean sableBroken = false;
	private static boolean createBroken = false;

	// ------------------------------------------------------------------ setting up
	static void init() {
		ModList mods = ModList.get();
		if (mods.isLoaded("sable"))
			listenForSable();
		NeoForge.EVENT_BUS.addListener((CommandEvent e) -> onCommand(e));
		NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> onUse(e));
		NeoForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> onExplosion(e));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> attachAll(e.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> forgetAll());
		if (mods.isLoaded("create"))
			NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> trains(e.getServer()));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void listenForSable() {
		try {
			Class<?> ready = Class.forName("dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent");
			Method level = ready.getMethod("getLevel");
			Method container = ready.getMethod("getContainer");
			Consumer<Event> listener = event -> {
				try {
					if (level.invoke(event) instanceof ServerLevel serverLevel)
						attach(serverLevel, container.invoke(event));
				} catch (Throwable t) {
					sableFailed("a world's craft could not be watched", t);
				}
			};
			NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) ready, (Consumer) listener);
		} catch (Throwable t) {
			sableFailed("Sable's craft could not be watched", t);
		}
	}

	private static void sableFailed(String what, Throwable t) {
		if (!sableBroken) {
			sableBroken = true;
			org.slf4j.LoggerFactory.getLogger("Dailies Companion").error("Dailies Companion: {}; craft_assembled and craft_lost are off. A different version of Sable?", what, t);
		}
	}

	private static void createFailed(Throwable t) {
		if (!createBroken) {
			createBroken = true;
			org.slf4j.LoggerFactory.getLogger("Dailies Companion").error("Dailies Companion: Create's trains could not be followed; train_departed and train_arrived are off. A different version of Create?", t);
		}
	}

	// ------------------------------------------------------------------ what players did just before
	private record Action(UUID player, Vec3 where, long time) {}

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
	private static synchronized @Nullable Action assemblyNear(Vec3 where, double radius) {
		long now = System.currentTimeMillis();
		Action best = null;
		for (Action a : ASSEMBLIES)
			if (now - a.time() <= ASSEMBLY_WINDOW_MILLIS && a.where().distanceToSqr(where) <= radius * radius && (best == null || a.time() > best.time()))
				best = a;
		return best;
	}

	private static synchronized void forgetAssembly(Action action) {
		ASSEMBLIES.remove(action);
	}

	private static synchronized boolean blastNear(ServerLevel level, AABB bounds) {
		long now = System.currentTimeMillis();
		for (Blast b : BLASTS)
			if (b.level() == level && now - b.time() <= EXPLOSION_WINDOW_MILLIS && bounds.inflate(b.radius() + 2).contains(b.where()))
				return true;
		return false;
	}

	// ------------------------------------------------------------------ Sable's craft
	private static final class Craft {
		final UUID id;
		String name = "";
		AABB bounds;
		double speed;
		double peakMass;
		boolean lostSent;

		Craft(UUID id, AABB bounds) {
			this.id = id;
			this.bounds = bounds;
		}
	}

	/** One world's craft: what is known of each, and those added since the last tick, looked at once they are complete */
	private static final class Watch {
		final ServerLevel level;
		final Map<UUID, Craft> crafts = new HashMap<>();
		final List<Object> added = new ArrayList<>();
		int ticks;

		Watch(ServerLevel level) {
			this.level = level;
		}
	}

	private static final Map<Object, Watch> WATCHES = Collections.synchronizedMap(new IdentityHashMap<>());

	private static void attachAll(MinecraftServer server) {
		if (sableBroken || !ModList.get().isLoaded("sable"))
			return;
		for (ServerLevel level : server.getAllLevels()) {
			try {
				Object container = Reflect.callStatic("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", "getContainer", new Class<?>[]{Level.class}, level);
				if (container != null)
					attach(level, container);
			} catch (Throwable t) {
				sableFailed("a world's craft could not be found", t);
				return;
			}
		}
	}

	private static void attach(ServerLevel level, @Nullable Object container) throws Exception {
		if (container == null || WATCHES.containsKey(container))
			return;
		Watch watch = new Watch(level);
		WATCHES.put(container, watch);
		Class<?> observerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelObserver");
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "onSubLevelAdded" -> guarded(() -> added(watch, args[0]));
				case "onSubLevelRemoved" -> guarded(() -> removed(watch, args[0], args[1]));
				case "tick" -> guarded(() -> tick(watch));
				case "hashCode" -> {
					return System.identityHashCode(proxy);
				}
				case "equals" -> {
					return proxy == args[0];
				}
				case "toString" -> {
					return "Dailies Companion craft watch";
				}
				default -> {
				}
			}
			return null;
		};
		Object observer = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[]{observerType}, handler);
		Reflect.call(container, "addObserver", new Class<?>[]{observerType}, observer);
		// Craft already there (loaded with the world) are known, never "assembled"
		for (Object subLevel : (Iterable<?>) Reflect.call(container, "getAllSubLevels", new Class<?>[0])) {
			Craft craft = describe(subLevel);
			if (craft != null)
				watch.crafts.put(craft.id, craft);
		}
	}

	private static void guarded(Runnable work) {
		try {
			work.run();
		} catch (Throwable t) {
			sableFailed("a craft event could not be read", t);
		}
	}

	private static void added(Watch watch, Object subLevel) {
		if (!sableBroken)
			watch.added.add(subLevel);
	}

	/** Once a tick: craft added since the last one, now that their name, bounds and origin are set; once a second, their mass */
	private static void tick(Watch watch) {
		if (sableBroken)
			return;
		if (!watch.added.isEmpty()) {
			List<Object> fresh = new ArrayList<>(watch.added);
			watch.added.clear();
			for (Object subLevel : fresh)
				arrived(watch, subLevel);
		}
		if (++watch.ticks % MASS_EVERY_TICKS == 0)
			weigh(watch);
	}

	private static void arrived(Watch watch, Object subLevel) {
		Craft craft = describe(subLevel);
		if (craft == null || watch.crafts.containsKey(craft.id))
			return;
		watch.crafts.put(craft.id, craft);
		craft.peakMass = mass(subLevel);
		UUID splitFrom = (UUID) Reflect.callQuiet(subLevel, "getSplitFromSubLevel");
		if (splitFrom != null) {
			// A piece broke off another craft: that one has split
			Craft parent = watch.crafts.get(splitFrom);
			if (parent != null && !parent.lostSent) {
				parent.lostSent = true;
				lost(watch, parent, "split");
			}
			return;
		}
		Vec3 centre = craft.bounds.getCenter();
		double radius = CompanionConfig.nearbyRadius();
		Action assembly = assemblyNear(centre, Math.max(radius, craft.bounds.getSize()));
		boolean fresh = false;
		try {
			fresh = Reflect.call(subLevel, "getLastSerializationPointer", new Class<?>[0]) == null;
		} catch (Throwable ignored) {
			// Without the answer only an assembly seen happening counts
		}
		if (assembly == null && !fresh)
			return;
		if (!CompanionConfig.enabled("craft_assembled"))
			return;
		if (assembly != null)
			forgetAssembly(assembly);
		ServerPlayer by = assembly == null || assembly.player() == null ? null : watch.level.getServer().getPlayerList().getPlayer(assembly.player());
		JsonObject data = data(craft);
		String label = craft.name.isBlank() ? "A craft was assembled" : "The craft \"" + craft.name + "\" was assembled";
		if (by != null) {
			data.addProperty("assembled_by", by.getGameProfile().getName());
			label = by.getGameProfile().getName() + " assembled " + (craft.name.isBlank() ? "a craft" : "the craft \"" + craft.name + "\"");
		}
		Set<ServerPlayer> to = new LinkedHashSet<>(near(watch.level, craft.bounds, radius));
		if (by != null)
			to.add(by);
		for (ServerPlayer p : to)
			DailiesCompanion.send(p, "craft_assembled", label, data);
	}

	private static void removed(Watch watch, Object subLevel, Object reason) {
		UUID id = (UUID) Reflect.callQuiet(subLevel, "getUniqueId");
		Craft craft = id == null ? null : watch.crafts.remove(id);
		if (craft == null || craft.lostSent || !"REMOVED".equals(String.valueOf(reason)))
			return; // unloaded with its chunk: nothing happened to it
		AABB bounds = craft.bounds;
		if (assemblyNear(bounds.getCenter(), CompanionConfig.nearbyRadius()) != null)
			return; // taken apart at an assembler on purpose
		craft.lostSent = true;
		lost(watch, craft, blastNear(watch.level, bounds) ? "explosion" : "destroyed");
	}

	private static void weigh(Watch watch) {
		Set<UUID> present = new HashSet<>();
		Object container = null;
		synchronized (WATCHES) {
			for (Map.Entry<Object, Watch> entry : WATCHES.entrySet())
				if (entry.getValue() == watch)
					container = entry.getKey();
		}
		if (container == null)
			return;
		Object all = Reflect.callQuiet(container, "getAllSubLevels");
		if (!(all instanceof Iterable<?> subLevels))
			return;
		for (Object subLevel : subLevels) {
			Craft known = describe(subLevel);
			if (known == null)
				continue;
			present.add(known.id);
			Craft craft = watch.crafts.computeIfAbsent(known.id, id -> known);
			craft.bounds = known.bounds;
			craft.name = known.name;
			craft.speed = known.speed;
			double mass = mass(subLevel);
			if (mass > craft.peakMass) {
				craft.peakMass = mass;
				craft.lostSent = false;
			} else if (!craft.lostSent && craft.peakMass > 0 && mass < craft.peakMass * 0.5) {
				craft.lostSent = true;
				JsonObject extra = new JsonObject();
				extra.addProperty("mass_before", round(craft.peakMass));
				extra.addProperty("mass_after", round(mass));
				lost(watch, craft, blastNear(watch.level, craft.bounds) ? "explosion" : "lost_blocks", extra);
			}
		}
		watch.crafts.keySet().retainAll(present);
	}

	private static void lost(Watch watch, Craft craft, String cause) {
		lost(watch, craft, cause, null);
	}

	private static void lost(Watch watch, Craft craft, String cause, @Nullable JsonObject extra) {
		if (!CompanionConfig.enabled("craft_lost"))
			return;
		JsonObject data = data(craft);
		data.addProperty("cause", cause);
		if (extra != null)
			extra.entrySet().forEach(e -> data.add(e.getKey(), e.getValue()));
		String what = craft.name.isBlank() ? "A craft" : "The craft \"" + craft.name + "\"";
		String label = switch (cause) {
			case "explosion" -> what + " was blown apart";
			case "split" -> what + " broke in two";
			case "lost_blocks" -> what + " lost most of itself";
			default -> what + " was destroyed";
		};
		for (ServerPlayer p : near(watch.level, craft.bounds, CompanionConfig.nearbyRadius()))
			DailiesCompanion.send(p, "craft_lost", label, data);
	}

	private static @Nullable Craft describe(Object subLevel) {
		UUID id = (UUID) Reflect.callQuiet(subLevel, "getUniqueId");
		if (id == null)
			return null;
		Object box = Reflect.callQuiet(subLevel, "boundingBox");
		AABB bounds = box == null ? null : new AABB(num(box, "minX"), num(box, "minY"), num(box, "minZ"), num(box, "maxX"), num(box, "maxY"), num(box, "maxZ"));
		if (bounds == null || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.maxY))
			return null;
		Craft craft = new Craft(id, bounds);
		Object name = Reflect.callQuiet(subLevel, "getName");
		craft.name = name == null ? "" : name.toString();
		try {
			Field velocity = subLevel.getClass().getField("latestLinearVelocity");
			Object v = velocity.get(subLevel);
			double x = v.getClass().getField("x").getDouble(v), y = v.getClass().getField("y").getDouble(v), z = v.getClass().getField("z").getDouble(v);
			craft.speed = Math.sqrt(x * x + y * y + z * z);
		} catch (Throwable ignored) {
			// Speed is a detail
		}
		return craft;
	}

	private static double mass(Object subLevel) {
		try {
			Object tracker = Reflect.call(subLevel, "getMassTracker", new Class<?>[0]);
			Object mass = tracker == null ? null : Reflect.call(tracker, "getMass", new Class<?>[0]);
			return mass instanceof Number n ? n.doubleValue() : 0.0;
		} catch (Throwable t) {
			return 0.0;
		}
	}

	private static double num(Object box, String method) {
		Object value = Reflect.callQuiet(box, method);
		return value instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	private static JsonObject data(Craft craft) {
		JsonObject data = new JsonObject();
		data.addProperty("craft_id", craft.id.toString());
		if (!craft.name.isBlank())
			data.addProperty("craft_name", craft.name);
		data.addProperty("speed", round(craft.speed));
		Vec3 centre = craft.bounds.getCenter();
		data.addProperty("x", round(centre.x));
		data.addProperty("y", round(centre.y));
		data.addProperty("z", round(centre.z));
		return data;
	}

	// ------------------------------------------------------------------ Create's trains
	private static final class TrainState {
		UUID station;
		String stationName = "";
	}

	private static final Map<UUID, TrainState> TRAINS = new HashMap<>();
	/** Train and player pairs that have departed: each departs once, then arrives once */
	private static final Set<String> DEPARTED = new HashSet<>();
	private static final Set<String> ARRIVING = new HashSet<>();
	private static int trainTicks = 0;

	private static void trains(MinecraftServer server) {
		if (createBroken || ++trainTicks % 10 != 0)
			return;
		try {
			Object railways = Class.forName("com.simibubi.create.Create").getField("RAILWAYS").get(null);
			Map<?, ?> trains = (Map<?, ?>) railways.getClass().getField("trains").get(railways);
			Set<UUID> present = new HashSet<>();
			for (Object train : new ArrayList<>(trains.values())) {
				UUID id = (UUID) train.getClass().getField("id").get(train);
				present.add(id);
				UUID station = (UUID) train.getClass().getField("currentStation").get(train);
				TrainState state = TRAINS.get(id);
				if (state == null) {
					state = new TrainState();
					state.station = station;
					state.stationName = stationName(train);
					TRAINS.put(id, state);
					continue;
				}
				if (state.station != null && station == null)
					departed(server, train, id, state.stationName);
				else if (state.station == null && station != null)
					arrived(server, train, id, stationName(train));
				state.station = station;
				if (station != null)
					state.stationName = stationName(train);
			}
			TRAINS.keySet().retainAll(present);
		} catch (Throwable t) {
			createFailed(t);
		}
	}

	private static String stationName(Object train) {
		try {
			Object station = Reflect.call(train, "getCurrentStation", new Class<?>[0]);
			Object name = station == null ? null : station.getClass().getField("name").get(station);
			return name == null ? "" : name.toString();
		} catch (Throwable t) {
			return "";
		}
	}

	private static String trainName(Object train) {
		try {
			Object name = train.getClass().getField("name").get(train);
			return name instanceof net.minecraft.network.chat.Component c ? c.getString() : "";
		} catch (Throwable t) {
			return "";
		}
	}

	private static void departed(MinecraftServer server, Object train, UUID id, String station) {
		if (!CompanionConfig.enabled("train_departed"))
			return;
		String name = trainName(train);
		for (ServerPlayer p : aboard(server, train)) {
			String key = id + "|" + p.getUUID();
			if (!DEPARTED.add(key))
				continue;
			ARRIVING.add(key);
			JsonObject data = new JsonObject();
			data.addProperty("train_id", id.toString());
			data.addProperty("train_name", name);
			data.addProperty("station", station);
			DailiesCompanion.send(p, "train_departed", (name.isBlank() ? "The train" : name) + " left " + (station.isBlank() ? "the station" : station), data);
		}
	}

	private static void arrived(MinecraftServer server, Object train, UUID id, String station) {
		if (!CompanionConfig.enabled("train_arrived"))
			return;
		String name = trainName(train);
		for (ServerPlayer p : aboard(server, train)) {
			if (!ARRIVING.remove(id + "|" + p.getUUID()))
				continue;
			JsonObject data = new JsonObject();
			data.addProperty("train_id", id.toString());
			data.addProperty("train_name", name);
			data.addProperty("station", station);
			DailiesCompanion.send(p, "train_arrived", (name.isBlank() ? "The train" : name) + " reached " + (station.isBlank() ? "its stop" : station), data);
		}
	}

	/** The players riding a train, seated or standing on one of its carriages */
	private static Set<ServerPlayer> aboard(MinecraftServer server, Object train) {
		Set<ServerPlayer> riders = new LinkedHashSet<>();
		try {
			List<?> carriages = (List<?>) train.getClass().getField("carriages").get(train);
			for (Object carriage : carriages) {
				Consumer<Object> each = entity -> {
					if (!(entity instanceof Entity carried))
						return;
					for (Entity passenger : carried.getIndirectPassengers())
						if (passenger instanceof ServerPlayer p && real(p))
							riders.add(p);
					if (carried.level() instanceof ServerLevel level)
						for (ServerPlayer p : level.players())
							if (real(p) && carried.getBoundingBox().inflate(0.5, 1.5, 0.5).contains(p.position()))
								riders.add(p);
				};
				Reflect.call(carriage, "forEachPresentEntity", new Class<?>[]{Consumer.class}, each);
			}
		} catch (Throwable t) {
			createFailed(t);
		}
		return riders;
	}

	// ------------------------------------------------------------------ helpers
	private static void forgetAll() {
		WATCHES.clear();
		TRAINS.clear();
		DEPARTED.clear();
		ARRIVING.clear();
		synchronized (CraftEvents.class) {
			ASSEMBLIES.clear();
			BLASTS.clear();
		}
	}

	private static boolean real(ServerPlayer p) {
		return !(p instanceof FakePlayer) && p.connection != null;
	}

	private static List<ServerPlayer> near(ServerLevel level, AABB bounds, double radius) {
		List<ServerPlayer> out = new ArrayList<>();
		AABB around = bounds.inflate(radius);
		for (ServerPlayer p : level.players())
			if (real(p) && around.contains(p.position()))
				out.add(p);
		return out;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	/** Methods found by name once and kept */
	private static final class Reflect {
		private static final Map<String, Method> METHODS = new HashMap<>();

		static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
			return find(target.getClass(), name, types).invoke(target, args);
		}

		static @Nullable Object callQuiet(Object target, String name) {
			try {
				return call(target, name, new Class<?>[0]);
			} catch (Throwable t) {
				return null;
			}
		}

		static Object callStatic(String className, String name, Class<?>[] types, Object... args) throws Exception {
			return find(Class.forName(className), name, types).invoke(null, args);
		}

		private static synchronized Method find(Class<?> type, String name, Class<?>[] types) throws NoSuchMethodException {
			String key = type.getName() + "#" + name + "/" + types.length;
			Method method = METHODS.get(key);
			if (method == null) {
				method = lookup(type, name, types);
				try {
					method.setAccessible(true);
				} catch (RuntimeException ignored) {
					// A public method of a public type needs no help
				}
				METHODS.put(key, method);
			}
			return method;
		}

		/** Public methods first, then the class's own and its parents', so interface methods reached through a hidden class still work */
		private static Method lookup(Class<?> type, String name, Class<?>[] types) throws NoSuchMethodException {
			try {
				return type.getMethod(name, types);
			} catch (NoSuchMethodException ignored) {
				// Keep looking
			}
			for (Class<?> at = type; at != null; at = at.getSuperclass()) {
				try {
					return at.getDeclaredMethod(name, types);
				} catch (NoSuchMethodException ignored) {
					// Keep looking
				}
			}
			throw new NoSuchMethodException(type.getName() + "." + name);
		}
	}
}
