package comedymonkey.dailies.mcp;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The token every request must carry once the MCP server listens beyond this computer (specification MCP-8), and the
 * lockout that makes guessing it pointless: five wrong tokens from one address inside a minute shut that address out
 * for a minute, whatever it sends meanwhile. The clock is passed in so tests can run it forward.
 */
public final class TokenGuard {

    public static final int MAX_WRONG = 5;
    public static final long WINDOW_MILLIS = 60_000;
    public static final long LOCKOUT_MILLIS = 60_000;

    public enum Verdict {
        OK, MISSING, WRONG, LOCKED
    }

    private static class Record {
        final ArrayDeque<Long> wrong = new ArrayDeque<>();
        long lockedUntil = 0;
    }

    private final byte[] token;
    private final Map<String, Record> records = new HashMap<>();

    public TokenGuard(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** A new token: 32 random bytes in URL-safe text */
    public static String generate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param address the client's address as text
     * @param authorization the request's Authorization header, or null
     */
    public synchronized Verdict check(String address, @Nullable String authorization, long nowMillis) {
        this.forget(nowMillis);
        Record record = this.records.get(address);
        if (record != null && nowMillis < record.lockedUntil) {
            return Verdict.LOCKED;
        }
        String presented = bearer(authorization);
        if (presented == null) {
            return Verdict.MISSING;
        }
        // An empty token would let "Bearer " through; a server without a token accepts nobody
        if (this.token.length > 0 && MessageDigest.isEqual(this.token, presented.getBytes(StandardCharsets.UTF_8))) {
            this.records.remove(address);
            return Verdict.OK;
        }
        if (record == null) {
            record = new Record();
            this.records.put(address, record);
        }
        record.wrong.addLast(nowMillis);
        while (!record.wrong.isEmpty() && nowMillis - record.wrong.peekFirst() >= WINDOW_MILLIS) {
            record.wrong.removeFirst();
        }
        if (record.wrong.size() >= MAX_WRONG) {
            record.wrong.clear();
            record.lockedUntil = nowMillis + LOCKOUT_MILLIS;
        }
        return Verdict.WRONG;
    }

    /** How much longer an address is shut out, in seconds rounded up; 0 when it is not */
    public synchronized long lockedSeconds(String address, long nowMillis) {
        Record record = this.records.get(address);
        return record == null || nowMillis >= record.lockedUntil ? 0 : (record.lockedUntil - nowMillis + 999) / 1000;
    }

    private void forget(long nowMillis) {
        // Addresses that have behaved for a while are dropped, so a flood of them cannot fill the memory
        if (this.records.size() < 256) {
            return;
        }
        Iterator<Record> iterator = this.records.values().iterator();
        while (iterator.hasNext()) {
            Record record = iterator.next();
            boolean stale = record.wrong.isEmpty() || nowMillis - record.wrong.peekLast() >= WINDOW_MILLIS;
            if (nowMillis >= record.lockedUntil && stale) {
                iterator.remove();
            }
        }
    }

    /** The token of "Bearer token", or null when the header is absent or of another kind */
    static @Nullable String bearer(@Nullable String authorization) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = trimmed.substring(7).trim();
        return value.isEmpty() ? null : value;
    }

}
