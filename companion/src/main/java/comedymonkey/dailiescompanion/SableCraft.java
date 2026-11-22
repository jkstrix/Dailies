package comedymonkey.dailiescompanion;

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

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/**
 * craft_assembled and craft_lost, from Sable's own classes, called directly. This class is loaded only when Sable is
 * installed (CraftEvents asks the mod list first), so the companion runs without it.
 *
 * <p>Calling Sable directly rather than by reflection matters on a dedicated server: Sable's classes declare a few
 * client-only methods (SubLevelContainer.getContainer(ClientLevel), for one), and a reflective lookup by name makes the
 * JVM load every type in every method signature of the class, ClientLevel among them, which a server cannot do. A direct
 * call resolves only the method called. Nothing called here, and no type in what is called, is client-only. Built
 * against Sable 2.0.6.
 */
final class SableCraft {
	private SableCraft() {}

	/** A craft appearing this soon after a player assembled something near it is theirs */
	private static final long ASSEMBLY_WINDOW_MILLIS = 5_000;
	/** Mass is looked at once a second */
	private static final int MASS_EVERY_TICKS = 20;

	private static volatile boolean broken = false;

	static void init() {
		NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ForgeSableSubLevelContainerReadyEvent.class, SableCraft::onContainerReady);
	}

	private static void onContainerReady(ForgeSableSubLevelContainerReadyEvent event) {
		try {
			if (event.getLevel() instanceof ServerLevel level)
				attach(level, event.getContainer());
		} catch (Throwable t) {
			failed("a world's craft could not be watched", t);
		}
	}

	private static void failed(String what, Throwable t) {
		if (!broken) {
			broken = true;
			CraftEvents.partFailed("Sable", "2.0.6", what, "craft_assembled and craft_lost", t);
		}
	}

	// ------------------------------------------------------------------ what is known of each craft
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
	private static final class Watch implements SubLevelObserver {
		final ServerLevel level;
		final SubLevelContainer container;
		final Map<UUID, Craft> crafts = new HashMap<>();
		final List<SubLevel> added = new ArrayList<>();
		int ticks;

		Watch(ServerLevel level, SubLevelContainer container) {
			this.level = level;
			this.container = container;
		}

		@Override
		public void onSubLevelAdded(SubLevel subLevel) {
			try {
				if (!broken)
					added.add(subLevel);
			} catch (Throwable t) {
				failed("a craft event could not be read", t);
			}
		}

		@Override
		public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
			try {
				if (!broken)
					removed(this, subLevel, reason);
			} catch (Throwable t) {
				failed("a craft event could not be read", t);
			}
		}

		@Override
		public void tick(SubLevelContainer ticked) {
			try {
				if (!broken)
					SableCraft.tick(this);
			} catch (Throwable t) {
				failed("a craft event could not be read", t);
			}
		}

		@Override
		public String toString() {
			return "Dailies Companion craft watch";
		}
	}

	private static final Map<SubLevelContainer, Watch> WATCHES = Collections.synchronizedMap(new IdentityHashMap<>());

	static void attachAll(MinecraftServer server) {
		if (broken)
			return;
		for (ServerLevel level : server.getAllLevels()) {
			try {
				attach(level, SubLevelContainer.getContainer((Level) level));
			} catch (Throwable t) {
				failed("a world's craft could not be found", t);
				return;
			}
		}
	}

	static void forgetAll() {
		WATCHES.clear();
	}

	private static void attach(ServerLevel level, @Nullable SubLevelContainer container) {
		if (container == null || WATCHES.containsKey(container))
			return;
		Watch watch = new Watch(level, container);
		WATCHES.put(container, watch);
		container.addObserver(watch);
		// Craft already there (loaded with the world) are known, never "assembled"
		for (SubLevel subLevel : container.getAllSubLevels()) {
			Craft craft = describe(subLevel);
			if (craft != null)
				watch.crafts.put(craft.id, craft);
		}
	}

	/** Once a tick: craft added since the last one, now that their name, bounds and origin are set; once a second, their mass */
	private static void tick(Watch watch) {
		if (!watch.added.isEmpty()) {
			List<SubLevel> fresh = new ArrayList<>(watch.added);
			watch.added.clear();
			for (SubLevel subLevel : fresh)
				arrived(watch, subLevel);
		}
		if (++watch.ticks % MASS_EVERY_TICKS == 0)
			weigh(watch);
	}

	private static void arrived(Watch watch, SubLevel subLevel) {
		Craft craft = describe(subLevel);
		if (craft == null || watch.crafts.containsKey(craft.id))
			return;
		watch.crafts.put(craft.id, craft);
		craft.peakMass = mass(subLevel);
		ServerSubLevel server = subLevel instanceof ServerSubLevel s ? s : null;
		UUID splitFrom = server == null ? null : server.getSplitFromSubLevel();
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
		CraftEvents.Action assembly = CraftEvents.assemblyNear(centre, Math.max(radius, craft.bounds.getSize()));
		// Never saved: made just now, not loaded with the world
		boolean fresh = false;
		try {
			fresh = server != null && server.getLastSerializationPointer() == null;
		} catch (RuntimeException ignored) {
			// Without the answer only an assembly seen happening counts
		}
		if (assembly == null && !fresh)
			return;
		if (!CompanionConfig.enabled("craft_assembled"))
			return;
		if (assembly != null)
			CraftEvents.forgetAssembly(assembly);
		ServerPlayer by = assembly == null || assembly.player() == null ? null : watch.level.getServer().getPlayerList().getPlayer(assembly.player());
		JsonObject data = data(craft);
		String label = craft.name.isBlank() ? "A craft was assembled" : "The craft \"" + craft.name + "\" was assembled";
		if (by != null) {
			data.addProperty("assembled_by", by.getGameProfile().getName());
			label = by.getGameProfile().getName() + " assembled " + (craft.name.isBlank() ? "a craft" : "the craft \"" + craft.name + "\"");
		}
		Set<ServerPlayer> to = new LinkedHashSet<>(CraftEvents.near(watch.level, craft.bounds, radius));
		if (by != null)
			to.add(by);
		for (ServerPlayer p : to)
			DailiesCompanion.send(p, "craft_assembled", label, data);
	}

	private static void removed(Watch watch, SubLevel subLevel, SubLevelRemovalReason reason) {
		UUID id = subLevel.getUniqueId();
		Craft craft = id == null ? null : watch.crafts.remove(id);
		if (craft == null || craft.lostSent || reason != SubLevelRemovalReason.REMOVED)
			return; // unloaded with its chunk: nothing happened to it
		AABB bounds = craft.bounds;
		if (CraftEvents.assemblyNear(bounds.getCenter(), CompanionConfig.nearbyRadius()) != null)
			return; // taken apart at an assembler on purpose
		craft.lostSent = true;
		lost(watch, craft, CraftEvents.blastNear(watch.level, bounds) ? "explosion" : "destroyed");
	}

	private static void weigh(Watch watch) {
		if (!WATCHES.containsKey(watch.container))
			return;
		Set<UUID> present = new HashSet<>();
		for (SubLevel subLevel : watch.container.getAllSubLevels()) {
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
				extra.addProperty("mass_before", CraftEvents.round(craft.peakMass));
				extra.addProperty("mass_after", CraftEvents.round(mass));
				lost(watch, craft, CraftEvents.blastNear(watch.level, craft.bounds) ? "explosion" : "lost_blocks", extra);
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
		for (ServerPlayer p : CraftEvents.near(watch.level, craft.bounds, CompanionConfig.nearbyRadius()))
			DailiesCompanion.send(p, "craft_lost", label, data);
	}

	private static @Nullable Craft describe(SubLevel subLevel) {
		UUID id = subLevel.getUniqueId();
		if (id == null)
			return null;
		BoundingBox3dc box = subLevel.boundingBox();
		AABB bounds = box == null ? null : new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		if (bounds == null || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.maxY))
			return null;
		Craft craft = new Craft(id, bounds);
		String name = subLevel.getName();
		craft.name = name == null ? "" : name;
		try {
			if (subLevel instanceof ServerSubLevel server && server.latestLinearVelocity != null)
				craft.speed = server.latestLinearVelocity.length();
		} catch (RuntimeException ignored) {
			// Speed is a detail
		}
		return craft;
	}

	private static double mass(SubLevel subLevel) {
		if (!(subLevel instanceof ServerSubLevel server))
			return 0.0;
		try {
			MassData tracker = server.getMassTracker();
			return tracker == null ? 0.0 : tracker.getMass();
		} catch (RuntimeException e) {
			return 0.0; // not worked out yet
		}
	}

	private static JsonObject data(Craft craft) {
		JsonObject data = new JsonObject();
		data.addProperty("craft_id", craft.id.toString());
		if (!craft.name.isBlank())
			data.addProperty("craft_name", craft.name);
		data.addProperty("speed", CraftEvents.round(craft.speed));
		Vec3 centre = craft.bounds.getCenter();
		data.addProperty("x", CraftEvents.round(centre.x));
		data.addProperty("y", CraftEvents.round(centre.y));
		data.addProperty("z", CraftEvents.round(centre.z));
		return data;
	}
}
