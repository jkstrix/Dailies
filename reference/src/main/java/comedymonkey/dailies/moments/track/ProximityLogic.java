package comedymonkey.dailies.moments.track;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who is standing with whom. Fed once per second with the positions of the visible players; produces meetings
 * (two players staying close) and group records (connected components of three or more).
 * <p>
 * Has no Minecraft dependencies so it can be driven with synthetic positions and simulated time (see the self test).
 * The "ref" objects are opaque to this class: the caller passes one per update and gets the ones from the start and
 * the end of each record back.
 */
public class ProximityLogic {

    /** A pair or group that drifted apart for this long has ended */
    public static final long APART_MILLIS = 10_000;
    /** Leaving one group and joining another within this time is a group_move */
    public static final long MOVE_WINDOW_MILLIS = 120_000;

    public record PlayerPos(String id, double x, double y, double z) {}

    public static class Interaction {
        public String type;
        public List<String> players;
        public long start;
        public long end;
        public double x, y, z;
        public Object startRef;
        public Object endRef;
        public boolean ongoing;
        @Nullable public String groupId;
        /** group_changed: who joined / left. group_dissolved: the members at the end */
        @Nullable public List<String> joined;
        @Nullable public List<String> left;
        /** group_move: the group that was left and its members */
        @Nullable public String fromGroupId;
        @Nullable public List<String> fromMembers;

        public double durationSeconds() {
            return (this.end - this.start) / 1000.0;
        }
    }

    private static class Pair {
        String a, b;
        long start, lastLinked;
        double sumX, sumY, sumZ;
        int samples;
        Object startRef, lastRef;
    }

    private static class Group {
        String id;
        Set<String> members;
        Set<String> allMembers;
        long start, lastSeen;
        double sumX, sumY, sumZ;
        int samples;
        Object startRef, lastRef;
        boolean announced;
    }

    private record Departure(String groupId, List<String> members, long time, Object ref) {}

    private final Map<String, Pair> pairs = new LinkedHashMap<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Departure> departures = new HashMap<>();
    private int nextGroupId = 1;
    private long minMillis = 20_000;

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * @param radius players within this distance are linked
     * @param minMillis how long a pair or group has to last before it is reported
     * @return the records that were completed (or, for group_formed / group_changed / group_move, happened) in this step
     */
    public synchronized List<Interaction> update(long now, List<PlayerPos> players, Object ref, double radius, long minMillis) {
        this.minMillis = minMillis;
        List<Interaction> out = new ArrayList<>();

        Map<String, PlayerPos> byId = new HashMap<>();
        for (PlayerPos player : players) {
            byId.put(player.id(), player);
        }

        // Union-find over the links, for the connected components
        Map<String, String> parent = new HashMap<>();
        for (PlayerPos player : players) {
            parent.put(player.id(), player.id());
        }

        double radiusSq = radius * radius;
        for (int i = 0; i < players.size(); i++) {
            PlayerPos a = players.get(i);
            for (int j = i + 1; j < players.size(); j++) {
                PlayerPos b = players.get(j);
                double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
                if (dx * dx + dy * dy + dz * dz > radiusSq) {
                    continue;
                }
                parent.put(find(parent, a.id()), find(parent, b.id()));

                String key = pairKey(a.id(), b.id());
                Pair pair = this.pairs.get(key);
                if (pair == null) {
                    pair = new Pair();
                    pair.a = a.id().compareTo(b.id()) < 0 ? a.id() : b.id();
                    pair.b = pair.a.equals(a.id()) ? b.id() : a.id();
                    pair.start = now;
                    pair.startRef = ref;
                    this.pairs.put(key, pair);
                }
                pair.lastLinked = now;
                pair.lastRef = ref;
                pair.sumX += (a.x() + b.x()) / 2;
                pair.sumY += (a.y() + b.y()) / 2;
                pair.sumZ += (a.z() + b.z()) / 2;
                pair.samples += 1;
            }
        }

        // Pairs end when apart for a while, or straight away when one of the two is no longer in view
        Iterator<Pair> pairIterator = this.pairs.values().iterator();
        while (pairIterator.hasNext()) {
            Pair pair = pairIterator.next();
            if (pair.lastLinked == now) {
                continue;
            }
            boolean bothVisible = byId.containsKey(pair.a) && byId.containsKey(pair.b);
            if (!bothVisible || now - pair.lastLinked >= APART_MILLIS) {
                pairIterator.remove();
                if (pair.lastLinked - pair.start >= minMillis) {
                    out.add(this.meeting(pair, false));
                }
            }
        }

        // Components of three or more
        Map<String, Set<String>> componentsByRoot = new LinkedHashMap<>();
        for (PlayerPos player : players) {
            componentsByRoot.computeIfAbsent(find(parent, player.id()), key -> new TreeSet<>()).add(player.id());
        }
        List<Set<String>> components = new ArrayList<>();
        for (Set<String> component : componentsByRoot.values()) {
            if (component.size() >= 3) {
                components.add(component);
            }
        }

        // An existing group continues as the component it shares the most members with (at least two)
        Iterator<Group> groupIterator = this.groups.iterator();
        List<Group> dissolved = new ArrayList<>();
        while (groupIterator.hasNext()) {
            Group group = groupIterator.next();
            Set<String> best = null;
            int bestOverlap = 1;
            for (Set<String> component : components) {
                int overlap = 0;
                for (String member : group.members) {
                    if (component.contains(member)) overlap += 1;
                }
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = component;
                }
            }

            if (best == null) {
                if (now - group.lastSeen >= APART_MILLIS) {
                    groupIterator.remove();
                    dissolved.add(group);
                }
                continue;
            }
            components.remove(best);

            if (!best.equals(group.members)) {
                List<String> joined = new ArrayList<>();
                List<String> left = new ArrayList<>();
                for (String member : best) {
                    if (!group.members.contains(member)) joined.add(member);
                }
                for (String member : group.members) {
                    if (!best.contains(member)) left.add(member);
                }
                List<String> before = new ArrayList<>(group.members);
                group.members = best;
                group.allMembers.addAll(best);

                if (group.announced) {
                    Interaction changed = this.groupRecord("group_changed", group, now, now, ref, ref, byId);
                    changed.joined = joined;
                    changed.left = left;
                    out.add(changed);
                    for (String member : left) {
                        this.departures.put(member, new Departure(group.id, before, now, ref));
                    }
                    for (String member : joined) {
                        this.checkMove(member, group, now, ref, out);
                    }
                }
            }

            group.lastSeen = now;
            group.lastRef = ref;
            this.accumulate(group, byId);

            if (!group.announced && now - group.start >= minMillis) {
                group.announced = true;
                out.add(this.groupRecord("group_formed", group, group.start, now, group.startRef, ref, byId));
                for (String member : group.members) {
                    this.checkMove(member, group, now, ref, out);
                }
            }
        }

        for (Group group : dissolved) {
            if (group.announced) {
                out.add(this.dissolvedRecord(group, false));
                for (String member : group.members) {
                    this.departures.put(member, new Departure(group.id, new ArrayList<>(group.members), group.lastSeen, group.lastRef));
                }
            }
        }

        for (Set<String> component : components) {
            Group group = new Group();
            group.id = "g" + this.nextGroupId++;
            group.members = component;
            group.allMembers = new TreeSet<>(component);
            group.start = now;
            group.lastSeen = now;
            group.startRef = ref;
            group.lastRef = ref;
            this.accumulate(group, byId);
            this.groups.add(group);
        }

        this.departures.values().removeIf(departure -> now - departure.time() > MOVE_WINDOW_MILLIS);
        return out;
    }

    /** Meetings and groups that are still going on, as records with ongoing = true. Does not end them */
    public synchronized List<Interaction> snapshotOpen() {
        List<Interaction> out = new ArrayList<>();
        for (Pair pair : this.pairs.values()) {
            if (pair.lastLinked - pair.start >= this.minMillis) {
                out.add(this.meeting(pair, true));
            }
        }
        for (Group group : this.groups) {
            if (group.announced) {
                out.add(this.dissolvedRecord(group, true));
            }
        }
        return out;
    }

    /** Forget everything, eg. when the session ends (take {@link #snapshotOpen} first) */
    public synchronized void clear() {
        this.pairs.clear();
        this.groups.clear();
        this.departures.clear();
    }

    private void checkMove(String member, Group group, long now, Object ref, List<Interaction> out) {
        Departure departure = this.departures.get(member);
        if (departure == null) {
            // The group they came from may still be within its grace period, not declared dissolved yet
            for (Group other : this.groups) {
                if (other != group && other.announced && other.lastSeen < now && other.members.contains(member)) {
                    departure = new Departure(other.id, new ArrayList<>(other.members), other.lastSeen, other.lastRef);
                    other.members.remove(member);
                    break;
                }
            }
        }
        if (departure == null ||departure.groupId().equals(group.id) || now - departure.time() > MOVE_WINDOW_MILLIS) {
            return;
        }
        this.departures.remove(member);

        Interaction move = new Interaction();
        move.type = "group_move";
        move.players = List.of(member);
        move.start = departure.time();
        move.end = now;
        move.startRef = departure.ref();
        move.endRef = ref;
        move.groupId = group.id;
        move.joined = new ArrayList<>(group.members);
        move.fromGroupId = departure.groupId();
        move.fromMembers = departure.members();
        if (group.samples > 0) {
            move.x = group.sumX / group.samples;
            move.y = group.sumY / group.samples;
            move.z = group.sumZ / group.samples;
        }
        out.add(move);
    }

    private void accumulate(Group group, Map<String, PlayerPos> byId) {
        double x = 0, y = 0, z = 0;
        int count = 0;
        for (String member : group.members) {
            PlayerPos pos = byId.get(member);
            if (pos != null) {
                x += pos.x();
                y += pos.y();
                z += pos.z();
                count += 1;
            }
        }
        if (count > 0) {
            group.sumX += x / count;
            group.sumY += y / count;
            group.sumZ += z / count;
            group.samples += 1;
        }
    }

    private Interaction meeting(Pair pair, boolean ongoing) {
        Interaction meeting = new Interaction();
        meeting.type = "meeting";
        meeting.players = List.of(pair.a, pair.b);
        meeting.start = pair.start;
        meeting.end = pair.lastLinked;
        meeting.startRef = pair.startRef;
        meeting.endRef = pair.lastRef;
        meeting.ongoing = ongoing;
        meeting.x = pair.sumX / pair.samples;
        meeting.y = pair.sumY / pair.samples;
        meeting.z = pair.sumZ / pair.samples;
        return meeting;
    }

    private Interaction groupRecord(String type, Group group, long start, long end, Object startRef, Object endRef, Map<String, PlayerPos> byId) {
        Interaction record = new Interaction();
        record.type = type;
        record.groupId = group.id;
        record.players = new ArrayList<>(group.members);
        record.start = start;
        record.end = end;
        record.startRef = startRef;
        record.endRef = endRef;
        if (group.samples > 0) {
            record.x = group.sumX / group.samples;
            record.y = group.sumY / group.samples;
            record.z = group.sumZ / group.samples;
        }
        return record;
    }

    /** The record for the whole life of a group: everyone who was ever part of it, from forming to drifting apart */
    private Interaction dissolvedRecord(Group group, boolean ongoing) {
        Interaction record = this.groupRecord("group_dissolved", group, group.start, group.lastSeen, group.startRef, group.lastRef, Map.of());
        record.players = new ArrayList<>(group.allMembers);
        record.left = new ArrayList<>(group.members);
        record.ongoing = ongoing;
        return record;
    }

    private static String find(Map<String, String> parent, String id) {
        String root = id;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // Path compression
        while (!parent.get(id).equals(root)) {
            String next = parent.get(id);
            parent.put(id, root);
            id = next;
        }
        return root;
    }

}
