package comedymonkey.dailies.mcp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which machines may talk to the MCP server once it listens beyond this computer (specification MCP-8): a list of
 * addresses and CIDR ranges, such as "192.168.1.20, 10.0.0.0/8, fd00::/8". This computer itself is always allowed.
 * Only address literals are understood; a name would have to be looked up, and a lookup can be lied to.
 */
public final class AddressRules {

    /** The private local-network ranges, IPv4 and IPv6 */
    public static final String LOCAL_NETWORKS = "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7, fe80::/10";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    private record Range(byte[] address, int prefix) {
        boolean holds(byte[] candidate) {
            if (candidate.length != this.address.length) {
                return false;
            }
            for (int bit = 0; bit < this.prefix; bit++) {
                int mask = 0x80 >> (bit % 8);
                if ((candidate[bit / 8] & mask) != (this.address[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Range> ranges = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();

    private AddressRules() {
    }

    /** Entries are separated by commas, spaces or new lines. Ones that make no sense are left out and listed in {@link #rejected} */
    public static AddressRules parse(String list) {
        AddressRules rules = new AddressRules();
        for (String entry : list.split("[,\\s]+")) {
            if (entry.isEmpty()) {
                continue;
            }
            Range range = range(entry);
            if (range == null) {
                rules.rejected.add(entry);
            } else {
                rules.ranges.add(range);
            }
        }
        return rules;
    }

    private static Range range(String entry) {
        int slash = entry.indexOf('/');
        byte[] address = literal(slash < 0 ? entry : entry.substring(0, slash));
        if (address == null) {
            return null;
        }
        int prefix = address.length * 8;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > address.length * 8) {
                return null;
            }
        }
        return new Range(address, prefix);
    }

    /** The bytes of an address written out in full, or null. Never asks a name server */
    static byte[] literal(String text) {
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        if (!IPV4.matcher(text).matches() && !IPV6.matcher(text).matches()) {
            return null;
        }
        try {
            // An IPv4 address written the IPv6 way comes back as four bytes, so both spellings match the same ranges
            return InetAddress.getByName(text).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean allows(InetAddress client) {
        if (client == null) {
            return false;
        }
        if (client.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = client.getAddress();
        for (Range range : this.ranges) {
            if (range.holds(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** Entries that could not be understood, for the log */
    public List<String> rejected() {
        return List.copyOf(this.rejected);
    }

    public int size() {
        return this.ranges.size();
    }

}
