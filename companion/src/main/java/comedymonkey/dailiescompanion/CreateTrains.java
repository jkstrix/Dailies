package comedymonkey.dailiescompanion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * train_departed and train_arrived, from Create's own classes, called directly. This class is loaded only when Create is
 * installed (CraftEvents asks the mod list first), so the companion runs without it. Direct calls resolve only what is
 * called, never a whole class's members, so a client-only member elsewhere in Create cannot stop a dedicated server.
 * Built against Create 6.0.10.
 */
final class CreateTrains {
	private CreateTrains() {}

	private static boolean broken = false;

	private static final class TrainState {
		UUID station;
		String stationName = "";
	}

	private static final Map<UUID, TrainState> TRAINS = new HashMap<>();
	/** Train and player pairs that have departed: each departs once, then arrives once */
	private static final Set<String> DEPARTED = new HashSet<>();
	private static final Set<String> ARRIVING = new HashSet<>();
	private static int trainTicks = 0;

	private static void failed(Throwable t) {
		if (!broken) {
			broken = true;
			CraftEvents.partFailed("Create", "6.0.10", "Create's trains could not be followed", "train_departed and train_arrived", t);
		}
	}

	static void tick(MinecraftServer server) {
		if (broken || ++trainTicks % 10 != 0)
			return;
		try {
			Set<UUID> present = new HashSet<>();
			for (Train train : new ArrayList<>(Create.RAILWAYS.trains.values())) {
				UUID id = train.id;
				present.add(id);
				UUID station = train.currentStation;
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
			failed(t);
		}
	}

	static void forgetAll() {
		TRAINS.clear();
		DEPARTED.clear();
		ARRIVING.clear();
	}

	private static String stationName(Train train) {
		try {
			GlobalStation station = train.getCurrentStation();
			return station == null || station.name == null ? "" : station.name;
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static String trainName(Train train) {
		return train.name == null ? "" : train.name.getString();
	}

	private static void departed(MinecraftServer server, Train train, UUID id, String station) {
		if (!CompanionConfig.enabled("train_departed"))
			return;
		String name = trainName(train);
		for (ServerPlayer p : aboard(train)) {
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

	private static void arrived(MinecraftServer server, Train train, UUID id, String station) {
		if (!CompanionConfig.enabled("train_arrived"))
			return;
		String name = trainName(train);
		for (ServerPlayer p : aboard(train)) {
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
	private static Set<ServerPlayer> aboard(Train train) {
		Set<ServerPlayer> riders = new LinkedHashSet<>();
		for (Carriage carriage : train.carriages) {
			carriage.forEachPresentEntity(entity -> {
				Entity carried = entity;
				for (Entity passenger : carried.getIndirectPassengers())
					if (passenger instanceof ServerPlayer p && CraftEvents.real(p))
						riders.add(p);
				if (carried.level() instanceof ServerLevel level)
					for (ServerPlayer p : level.players())
						if (CraftEvents.real(p) && carried.getBoundingBox().inflate(0.5, 1.5, 0.5).contains(p.position()))
							riders.add(p);
			});
		}
		return riders;
	}
}
