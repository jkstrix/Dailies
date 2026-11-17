package comedymonkey.dailies.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Checking what was typed before it is taken: a number against its range, the address of a server against what an
 * address can be. Plain Java, so it can be tested without the game.
 */
final class Checks {

    /** What is wrong with a number that was typed */
    enum NumberProblem {
        NONE, NOT_A_NUMBER, NOT_WHOLE, TOO_LOW, TOO_HIGH
    }

    /**
     * @param value   the number as typed, when it could be read at all
     * @param nearest the allowed value closest to what was typed: what to offer in place of a refused one
     */
    record Number(NumberProblem problem, double value, double nearest) {
        boolean ok() {
            return this.problem == NumberProblem.NONE;
        }
    }

    /** What is wrong with an address that was typed */
    enum AddressProblem {
        NONE, EMPTY, NO_SCHEME, NOT_HTTP, NO_HOST, BAD_PORT, BAD_CHARACTERS
    }

    /** The settings that hold addresses come in two kinds: a whole URL, and the bare name or number of a machine */
    enum AddressKind {
        URL, HOST
    }

    private Checks() {
    }

    /** The value moved into the range, and onto a whole number where only those are allowed */
    static double clamp(double value, double min, double max, boolean whole) {
        double inside = Math.max(min, Math.min(max, value));
        if (whole) {
            inside = Math.max(Math.ceil(min), Math.min(Math.floor(max), Math.rint(inside)));
        }
        return inside;
    }

    /** Reads a number as a person types one (a comma is taken for the decimal point) and holds it against its range */
    static Number number(String typed, double min, double max, boolean whole) {
        double value;
        try {
            value = Double.parseDouble(typed == null ? "" : typed.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return new Number(NumberProblem.NOT_A_NUMBER, Double.NaN, Double.NaN);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new Number(NumberProblem.NOT_A_NUMBER, Double.NaN, Double.NaN);
        }
        double nearest = clamp(value, min, max, whole);
        if (value < min) {
            return new Number(NumberProblem.TOO_LOW, value, nearest);
        }
        if (value > max) {
            return new Number(NumberProblem.TOO_HIGH, value, nearest);
        }
        if (whole && value != Math.rint(value)) {
            return new Number(NumberProblem.NOT_WHOLE, value, nearest);
        }
        return new Number(NumberProblem.NONE, value, value);
    }

    /** Whether a setting of this name holds an address, and of which kind; null when it does not */
    static AddressKind addressKind(String settingName) {
        String name = settingName == null ? "" : settingName.toLowerCase(Locale.ROOT);
        if (name.endsWith("url") || name.endsWith("uri") || name.endsWith("endpoint")) {
            return AddressKind.URL;
        }
        if (name.endsWith("address") || name.endsWith("host") || name.endsWith("bind")) {
            return AddressKind.HOST;
        }
        return null;
    }

    /** An http or https address with a host, and a port when one is given. Empty is reported as such, not as wrong */
    static AddressProblem url(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) {
            return AddressProblem.EMPTY;
        }
        if (text.chars().anyMatch(c -> Character.isWhitespace(c) || c < 0x20 || c == '"' || c == '<' || c == '>')) {
            return AddressProblem.BAD_CHARACTERS;
        }
        int scheme = text.indexOf("://");
        if (scheme < 0) {
            return AddressProblem.NO_SCHEME;
        }
        String kind = text.substring(0, scheme).toLowerCase(Locale.ROOT);
        if (!kind.equals("http") && !kind.equals("https")) {
            return AddressProblem.NOT_HTTP;
        }
        String rest = text.substring(scheme + 3);
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        String authority = rest.substring(0, end);
        if (authority.contains("@")) {
            authority = authority.substring(authority.lastIndexOf('@') + 1);
        }
        AddressProblem machine = hostAndPort(authority, true);
        if (machine != AddressProblem.NONE) {
            return machine;
        }
        try {
            new URI(text);
        } catch (URISyntaxException e) {
            return AddressProblem.BAD_CHARACTERS;
        }
        return AddressProblem.NONE;
    }

    /** The name or number of a machine with nothing round it, as a server is told where to listen: 127.0.0.1, localhost, ::1 */
    static AddressProblem host(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) {
            return AddressProblem.EMPTY;
        }
        if (text.contains("://") || text.contains("/")) {
            return AddressProblem.BAD_CHARACTERS;
        }
        return hostAndPort(text, false);
    }

    private static AddressProblem hostAndPort(String authority, boolean portAllowed) {
        String host = authority;
        String port = null;
        if (authority.startsWith("[")) {
            // An IPv6 number in its brackets
            int close = authority.indexOf(']');
            if (close < 0) {
                return AddressProblem.BAD_CHARACTERS;
            }
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    return AddressProblem.BAD_CHARACTERS;
                }
                port = after.substring(1);
            }
            if (host.isEmpty()) {
                return AddressProblem.NO_HOST;
            }
            if (!host.matches("[0-9a-fA-F:.]+") || !host.contains(":")) {
                return AddressProblem.BAD_CHARACTERS;
            }
        } else {
            long colons = authority.chars().filter(c -> c == ':').count();
            if (colons == 1) {
                host = authority.substring(0, authority.indexOf(':'));
                port = authority.substring(authority.indexOf(':') + 1);
            } else if (colons > 1) {
                // A bare IPv6 number: only where no port can follow
                if (portAllowed || !authority.matches("[0-9a-fA-F:.]+")) {
                    return AddressProblem.BAD_CHARACTERS;
                }
                return AddressProblem.NONE;
            }
            if (host.isEmpty()) {
                return AddressProblem.NO_HOST;
            }
            if (!host.matches("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?") || host.contains("..")) {
                return AddressProblem.BAD_CHARACTERS;
            }
            if (host.matches("[0-9.]+") && !ipv4(host)) {
                return AddressProblem.BAD_CHARACTERS;
            }
        }
        if (port != null) {
            if (!portAllowed) {
                return AddressProblem.BAD_PORT;
            }
            if (!port.matches("[0-9]{1,5}")) {
                return AddressProblem.BAD_PORT;
            }
            int number = Integer.parseInt(port);
            if (number < 1 || number > 65535) {
                return AddressProblem.BAD_PORT;
            }
        }
        return AddressProblem.NONE;
    }

    private static boolean ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

}
