package comedymonkey.dailies.mcp;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which Host and Origin headers the MCP server answers (specification MCP-3, MCP-8). Left alone it answers only to
 * localhost, which stops web pages and DNS rebinding from driving the game. Listening beyond this computer widens
 * the list by exactly the names the server can be reached under, and no further.
 */
public final class HostRules {

    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", bare("::1"));

    private final Set<String> hosts = new LinkedHashSet<>(LOCAL);

    private HostRules() {
    }

    /** Localhost only, as the server has always been */
    public static HostRules local() {
        return new HostRules();
    }

    /**
     * @param bind the address the server listens on
     * @param ownNames when the bind address means "every interface": the addresses and name of this computer
     */
    public static HostRules forBind(String bind, Collection<String> ownNames) {
        HostRules rules = new HostRules();
        if (isWildcard(bind)) {
            for (String name : ownNames) {
                rules.hosts.add(bare(name));
            }
        } else {
            rules.hosts.add(bare(bind));
        }
        return rules;
    }

    /** Whether a bind address keeps the server on this computer alone */
    public static boolean isLocalBind(String bind) {
        return LOCAL.contains(bare(bind));
    }

    public static boolean isWildcard(String bind) {
        String bare = bare(bind);
        return bare.equals("0.0.0.0") || bare.equals("::") || bare.equals("0:0:0:0:0:0:0:0");
    }

    /** A request without a Host header is let through, as before: only browsers are the worry, and they always send one */
    public boolean hostAllowed(@Nullable String hostHeader) {
        return hostHeader == null || this.hosts.contains(bare(stripPort(hostHeader.trim())));
    }

    /** Non-browser clients send no Origin at all; a browser's must be a page this server itself served */
    public boolean originAllowed(@Nullable String origin) {
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                return false;
            }
            return this.hosts.contains(bare(uri.getHost()));
        } catch (Exception e) {
            return false;
        }
    }

    public Set<String> hosts() {
        return Set.copyOf(this.hosts);
    }

    private static String bare(String host) {
        String text = host.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        // "fe80::1%eth0" names an interface as well as an address; headers never carry that part
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.indexOf(':') >= 0) {
            // IPv6 addresses have many spellings; compare them all in the one Java writes
            byte[] address = AddressRules.literal(text);
            if (address != null) {
                try {
                    return java.net.InetAddress.getByAddress(address).getHostAddress();
                } catch (Exception ignored) {
                }
            }
        }
        return text;
    }

    static String stripPort(String host) {
        if (host.startsWith("[")) { // IPv6 literal
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.lastIndexOf(':');
        // More than one colon and no brackets is a bare IPv6 address, which has no port to strip
        return colon < 0 || host.indexOf(':') != colon ? host : host.substring(0, colon);
    }

}
