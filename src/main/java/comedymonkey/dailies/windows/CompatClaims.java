package comedymonkey.dailies.windows;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The compats registered in this game and what each claims (specification REC-26, CPT-1): screens by class name, server
 * windows by menu type, extras for windows Dailies draws, overlays. It answers which claim, if any, takes a screen, and
 * which compats a recording lists that this game is without. The parts themselves are kept as they were given and never
 * looked at here: no game in here, so it is unit tested.
 */
final class CompatClaims {

    enum Kind {
        /** A screen claimed by its class */
        SCREEN,
        /** A server window claimed whole by its menu type */
        MENU,
        /** Extras for a server window Dailies records and draws */
        EXTRAS,
        OVERLAY
    }

    /** A registered compat */
    record Compat(String id, int version, Object compat) {
    }

    /**
     * One claim.
     *
     * @param key  the screen's class name, or the menu type; empty for an overlay
     * @param part the part the compat gave, of the type its kind takes
     */
    record Claim(String compatId, int version, String name, Kind kind, String key, Object part) {

        /** What the log, the display test and the drawn-last report call it: {@code <compat id>/<name>} */
        String drawnAs() {
            return this.compatId + "/" + this.name;
        }
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]{1,64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> RESERVED = Set.of("dailies", "minecraft");

    private final Map<String, Compat> compats = new LinkedHashMap<>();
    private final Map<String, Map<String, Claim>> byCompat = new HashMap<>();
    private final Map<String, Claim> screens = new HashMap<>();
    private final Map<String, Claim> menus = new HashMap<>();
    private final Map<String, Claim> extras = new HashMap<>();
    private final List<Claim> overlays = new ArrayList<>();
    /** Notes already given about recordings, so each is given once */
    private final Set<String> noted = new HashSet<>();

    /**
     * @throws IllegalArgumentException when the id is not a namespaced id, is in a namespace of Dailies' own, is taken,
     *                                  or the version is below 1
     */
    synchronized Compat add(String id, int version, Object compat) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A compat needs a namespaced id in lower case, such as 'mymod:screens', not '" + id + "'");
        }
        if (RESERVED.contains(id.substring(0, id.indexOf(':')))) {
            throw new IllegalArgumentException("The compat id " + id + " is in a namespace of Dailies' own; use the mod's own");
        }
        if (version < 1) {
            throw new IllegalArgumentException("The compat " + id + " has version " + version + "; versions start at 1");
        }
        if (this.compats.containsKey(id)) {
            throw new IllegalArgumentException("A compat called " + id + " is registered already");
        }
        Compat added = new Compat(id, version, compat);
        this.compats.put(id, added);
        this.byCompat.put(id, new LinkedHashMap<>());
        return added;
    }

    /**
     * Takes a claim.
     *
     * @return null when it was taken, or why it was left out: another compat has the screen class or menu type already
     * @throws IllegalArgumentException when the name cannot be carried by a record, or the compat has a claim of that name
     */
    synchronized @Nullable String claim(Claim claim) {
        if (claim.name() == null || !NAME.matcher(claim.name()).matches()) {
            throw new IllegalArgumentException("The compat " + claim.compatId() + " has a claim called '" + claim.name()
                + "': names are 1 to 64 lower-case letters, digits and _ . / -");
        }
        Map<String, Claim> own = this.byCompat.get(claim.compatId());
        if (own == null) {
            throw new IllegalArgumentException("The compat " + claim.compatId() + " is not registered");
        }
        if (own.containsKey(claim.name())) {
            throw new IllegalArgumentException("The compat " + claim.compatId() + " has a claim called " + claim.name() + " already");
        }
        Map<String, Claim> keyed = switch (claim.kind()) {
            case SCREEN -> this.screens;
            case MENU -> this.menus;
            case EXTRAS -> this.extras;
            case OVERLAY -> null;
        };
        if (keyed != null) {
            String key = claim.kind() == Kind.SCREEN ? claim.key() : claim.key().toLowerCase(Locale.ROOT);
            Claim before = keyed.get(key);
            // A window is drawn whole by one compat, or by Dailies with extras: never both
            Claim other = claim.kind() == Kind.MENU ? this.extras.get(key) : claim.kind() == Kind.EXTRAS ? this.menus.get(key) : null;
            Claim taken = before != null ? before : other;
            if (taken != null) {
                return (claim.kind() == Kind.SCREEN ? "the screen " : "the window ") + claim.key() + " is claimed by " + taken.drawnAs() + " already";
            }
            keyed.put(key, claim);
        } else {
            this.overlays.add(claim);
        }
        own.put(claim.name(), claim);
        return null;
    }

    /**
     * The claim that takes a screen whole, or null when none does and Dailies' own window recording may take it: a claim
     * on its own class first, then on its menu's type, then on the classes it extends, nearest first.
     *
     * @param classes  the screen's class name and those of the classes it extends, its own first
     * @param menuType its menu's type id, or null when it has none
     */
    synchronized @Nullable Claim screenFor(List<String> classes, @Nullable String menuType) {
        if (classes.isEmpty()) {
            return null;
        }
        Claim own = this.screens.get(classes.get(0));
        if (own != null) {
            return own;
        }
        if (menuType != null) {
            Claim menu = this.menus.get(menuType.toLowerCase(Locale.ROOT));
            if (menu != null) {
                return menu;
            }
        }
        for (int i = 1; i < classes.size(); i++) {
            Claim inherited = this.screens.get(classes.get(i));
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    /** The extras for a window Dailies draws, by its menu type, or null */
    synchronized @Nullable Claim extrasFor(@Nullable String menuType) {
        return menuType == null ? null : this.extras.get(menuType.toLowerCase(Locale.ROOT));
    }

    synchronized List<Claim> overlays() {
        return List.copyOf(this.overlays);
    }

    synchronized @Nullable Claim claim(String compatId, String name) {
        Map<String, Claim> own = this.byCompat.get(compatId);
        return own == null ? null : own.get(name);
    }

    /** A compat's claims, in the order it made them */
    synchronized List<Claim> claimsOf(String compatId) {
        Map<String, Claim> own = this.byCompat.get(compatId);
        return own == null ? List.of() : List.copyOf(own.values());
    }

    synchronized @Nullable Compat compat(String id) {
        return this.compats.get(id);
    }

    synchronized List<Compat> compats() {
        return List.copyOf(this.compats.values());
    }

    /**
     * What to say about the compats a recording lists, each thing once in this game: a compat this game is without (its
     * layer is left out), and one the recording was made with a newer version of (drawn as far as this one can).
     *
     * @param listed the compats the recording lists, by id, with the version each wrote with
     * @return the notes not given before, each "missing id" or "newer id recorded installed"
     */
    synchronized List<String> notesFor(Map<String, Integer> listed) {
        if (listed == null || listed.isEmpty()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : listed.entrySet()) {
            Compat here = this.compats.get(entry.getKey());
            String note;
            if (here == null) {
                note = "missing " + entry.getKey();
            } else if (entry.getValue() != null && entry.getValue() > here.version()) {
                note = "newer " + entry.getKey() + " " + entry.getValue() + " " + here.version();
            } else {
                continue;
            }
            if (this.noted.add(note)) {
                notes.add(note);
            }
        }
        return Collections.unmodifiableList(notes);
    }

}
