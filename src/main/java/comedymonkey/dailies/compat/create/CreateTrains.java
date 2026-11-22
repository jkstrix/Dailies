package comedymonkey.dailies.compat.create;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Create's trains. A game is told of each train, and of every track network with its stations and signals, once, as it
 * joins, and of changes afterwards; a carriage looks its train up the moment it arrives and cannot be placed on the
 * track without its network. A recording that starts after a train was built, and every jump, would otherwise have
 * carriages that never find their train. So each snapshot tells playback of every network, its signal groups and every
 * train, in Create's own messages, as a server tells a player who has just joined, before the carriages are spawned;
 * and playback forgets the trains and networks it was told of before it reads a snapshot.
 * <p>
 * Nothing is compiled against Create: its classes are found by name, once. If any is missing, one line is logged and
 * trains are left to the messages recorded while they changed.
 */
public final class CreateTrains {

    private static final String EXTENSION = "dailies:create_trains";
    private static final String TRAINS = "com.simibubi.create.content.trains.";
    /** Create splits a network into messages of about this many parts, and so does this */
    private static final int PER_MESSAGE = 1000;

    private Field railways, trains, networks, edgeGroups;
    private Field graphId, graphNetId, graphNodes, graphConnections;
    private Field syncNodes, syncEdges, syncPoints, syncFullWipe;
    private Field groupColor, pointTypes;
    private Method nodeNetId, nodeLocation, nodeNormal, edgeMaterial, edgeTurn, edgeData, dataPoints, graphPoints, syncEdgeData, coupleOf, pairOf;
    private Constructor<?> addTrain, graphSync, groupPacket;
    private StreamCodec<RegistryFriendlyByteBuf, Object> addTrainCodec;
    private StreamCodec<FriendlyByteBuf, Object> graphSyncCodec, groupCodec;
    private boolean failed = false;

    private CreateTrains() {
    }

    @SuppressWarnings("unchecked")
    public static void init() {
        if (!Host.get().isModLoaded("create")) {
            return;
        }
        CreateTrains compat = new CreateTrains();
        try {
            Class<?> client = Class.forName("com.simibubi.create.CreateClient");
            Class<?> manager = Class.forName(TRAINS + "GlobalRailwayManager");
            Class<?> graph = Class.forName(TRAINS + "graph.TrackGraph");
            Class<?> node = Class.forName(TRAINS + "graph.TrackNode");
            Class<?> edge = Class.forName(TRAINS + "graph.TrackEdge");
            Class<?> data = Class.forName(TRAINS + "graph.EdgeData");
            Class<?> pointType = Class.forName(TRAINS + "graph.EdgePointType");
            Class<?> sync = Class.forName(TRAINS + "graph.TrackGraphSyncPacket");
            Class<?> add = Class.forName(TRAINS + "entity.AddTrainPacket");
            Class<?> train = Class.forName(TRAINS + "entity.Train");
            Class<?> group = Class.forName(TRAINS + "signal.SignalEdgeGroup");
            Class<?> groupMessage = Class.forName(TRAINS + "signal.SignalEdgeGroupPacket");
            Class<?> couple = Class.forName("net.createmod.catnip.data.Couple");
            Class<?> pair = Class.forName("net.createmod.catnip.data.Pair");
            compat.railways = field(client, "RAILWAYS");
            compat.trains = field(manager, "trains");
            compat.networks = field(manager, "trackNetworks");
            compat.edgeGroups = field(manager, "signalEdgeGroups");
            compat.graphId = field(graph, "id");
            compat.graphNetId = field(graph, "netId");
            compat.graphNodes = field(graph, "nodes");
            compat.graphConnections = field(graph, "connectionsByNode");
            compat.graphPoints = graph.getMethod("getPoints", pointType);
            compat.pointTypes = field(pointType, "TYPES");
            compat.nodeNetId = node.getMethod("getNetId");
            compat.nodeLocation = node.getMethod("getLocation");
            compat.nodeNormal = node.getMethod("getNormal");
            compat.edgeMaterial = edge.getMethod("getTrackMaterial");
            compat.edgeTurn = edge.getMethod("getTurn");
            compat.edgeData = edge.getMethod("getEdgeData");
            compat.dataPoints = data.getMethod("getPoints");
            compat.syncNodes = field(sync, "addedNodes");
            compat.syncEdges = field(sync, "addedEdges");
            compat.syncPoints = field(sync, "addedEdgePoints");
            compat.syncFullWipe = field(sync, "fullWipe");
            compat.syncEdgeData = sync.getMethod("syncEdgeData", node, node, edge);
            compat.graphSync = sync.getConstructor(UUID.class, int.class);
            compat.graphSyncCodec = (StreamCodec<FriendlyByteBuf, Object>) field(sync, "STREAM_CODEC").get(null);
            compat.addTrain = add.getConstructor(train);
            compat.addTrainCodec = (StreamCodec<RegistryFriendlyByteBuf, Object>) field(add, "STREAM_CODEC").get(null);
            compat.groupColor = field(group, "color");
            compat.groupPacket = groupMessage.getConstructor(List.class, List.class, boolean.class);
            compat.groupCodec = (StreamCodec<FriendlyByteBuf, Object>) field(groupMessage, "STREAM_CODEC").get(null);
            compat.coupleOf = couple.getMethod("create", Object.class, Object.class);
            compat.pairOf = pair.getMethod("of", Object.class, Object.class);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: this version of Create is not the one Dailies knows (6.0.10), so a train that was built before a recording began, or is jumped to, may not show in playback. Everything else works", t);
            return;
        }
        SnapshotExtensions.registerBeforeEntities(EXTENSION, compat::write, true);
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                // Nothing is written under this name: Create's own messages carry it all
            }

            @Override
            public void reset() {
                compat.forget();
            }
        });
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Object manager() throws IllegalAccessException {
        return this.railways.get(null);
    }

    /** What playback was told before a snapshot is read: the snapshot tells it everything again */
    private void forget() {
        if (this.failed) {
            return;
        }
        try {
            Object manager = this.manager();
            ((Map<?, ?>) this.trains.get(manager)).clear();
            ((Map<?, ?>) this.networks.get(manager)).clear();
            ((Map<?, ?>) this.edgeGroups.get(manager)).clear();
        } catch (Throwable t) {
            this.fail("forgetting the trains before a jump", t);
        }
    }

    /** The networks, their signal groups, then the trains, as Create tells a player who has just joined */
    @SuppressWarnings("unchecked")
    private void write(Recorder.Out out, GameCodec codec) throws Exception {
        if (this.failed) {
            return;
        }
        Object manager = this.manager();
        Map<UUID, Object> graphs = (Map<UUID, Object>) this.networks.get(manager);
        Map<UUID, Object> groups = (Map<UUID, Object>) this.edgeGroups.get(manager);
        Map<UUID, Object> allTrains = (Map<UUID, Object>) this.trains.get(manager);
        if (graphs.isEmpty() && allTrains.isEmpty()) {
            return;
        }
        try {
            // Gathered whole before anything is written, so that a failure leaves no half of a network behind
            List<Object> messages = new ArrayList<>();
            for (Object graph : new ArrayList<>(graphs.values())) {
                messages.addAll(this.describe(graph));
            }
            List<byte[]> frames = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Object message : messages) {
                frames.add(this.frame(codec, message, buffer -> this.graphSyncCodec.encode(buffer, message)));
                ids.add(id(message));
            }
            if (!groups.isEmpty()) {
                List<UUID> groupIds = new ArrayList<>();
                List<Object> colours = new ArrayList<>();
                for (Map.Entry<UUID, Object> group : groups.entrySet()) {
                    groupIds.add(group.getKey());
                    colours.add(this.groupColor.get(group.getValue()));
                }
                Object message = this.groupPacket.newInstance(groupIds, colours, true);
                frames.add(this.frame(codec, message, buffer -> this.groupCodec.encode(buffer, message)));
                ids.add(id(message));
            }
            for (Object train : new ArrayList<>(allTrains.values())) {
                Object message = this.addTrain.newInstance(train);
                frames.add(this.frame(codec, message, buffer -> this.addTrainCodec.encode(buffer, message)));
                ids.add(id(message));
            }
            for (int i = 0; i < frames.size(); i++) {
                out.payloadFrame(ids.get(i), frames.get(i));
            }
        } catch (Throwable t) {
            this.fail("describing the trains for a snapshot", t);
        }
    }

    private byte[] frame(GameCodec codec, Object message, java.util.function.Consumer<RegistryFriendlyByteBuf> body) {
        return codec.payloadFrame(((CustomPacketPayload) message).type().id(), body);
    }

    private static String id(Object message) {
        return ((CustomPacketPayload) message).type().id().toString();
    }

    /** One network as Create's messages that build it from nothing: its nodes, its track with what lies along it, and its stations and signals */
    @SuppressWarnings("unchecked")
    private List<Object> describe(Object graph) throws Exception {
        List<Object> messages = new ArrayList<>();
        UUID id = (UUID) this.graphId.get(graph);
        int netId = this.graphNetId.getInt(graph);
        Object message = this.graphSync.newInstance(id, netId);
        this.syncFullWipe.setBoolean(message, true);
        messages.add(message);
        int parts = 0;
        Collection<Object> nodes = new ArrayList<>(((Map<Object, Object>) this.graphNodes.get(graph)).values());
        for (Object node : nodes) {
            ((Map<Integer, Object>) this.syncNodes.get(message)).put((Integer) this.nodeNetId.invoke(node), this.pairOf.invoke(null, this.nodeLocation.invoke(node), this.nodeNormal.invoke(node)));
            if (++parts >= PER_MESSAGE) {
                parts = 0;
                message = this.graphSync.newInstance(id, netId);
                messages.add(message);
            }
        }
        Set<Object> pointsSent = new HashSet<>();
        Map<Object, Map<Object, Object>> connections = (Map<Object, Map<Object, Object>>) this.graphConnections.get(graph);
        for (Object node : nodes) {
            Map<Object, Object> from = connections.get(node);
            if (from == null) {
                continue;
            }
            for (Map.Entry<Object, Object> entry : from.entrySet()) {
                Object other = entry.getKey();
                Object edge = entry.getValue();
                Object key = this.coupleOf.invoke(null, this.nodeNetId.invoke(node), this.nodeNetId.invoke(other));
                ((List<Object>) this.syncEdges.get(message)).add(this.pairOf.invoke(null, this.pairOf.invoke(null, key, this.edgeMaterial.invoke(edge)), this.edgeTurn.invoke(edge)));
                this.syncEdgeData.invoke(message, node, other, edge);
                for (Object point : (List<Object>) this.dataPoints.invoke(this.edgeData.invoke(edge))) {
                    if (pointsSent.add(point)) {
                        ((List<Object>) this.syncPoints.get(message)).add(point);
                        parts++;
                    }
                }
                // Create's own messages split only between nodes, so an edge and its data always travel together
            }
            if (++parts >= PER_MESSAGE) {
                parts = 0;
                message = this.graphSync.newInstance(id, netId);
                messages.add(message);
            }
        }
        for (Object type : new ArrayList<>(((Map<Object, Object>) this.pointTypes.get(null)).values())) {
            for (Object point : (Collection<Object>) this.graphPoints.invoke(graph, type)) {
                if (pointsSent.add(point)) {
                    ((List<Object>) this.syncPoints.get(message)).add(point);
                }
            }
        }
        return messages;
    }

    private void fail(String what, Throwable problem) {
        this.failed = true;
        Host.LOGGER.error("Dailies: {} failed, so a train that was built before a recording began, or is jumped to, may not show in playback. Said once", what, problem);
    }

}
