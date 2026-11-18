package comedymonkey.dailies.moments.track;

/**
 * How many events a server may send in a while (a token bucket): a few at once, then so many a second. Any server
 * can send the companion's events, with or without the companion, and each one is kept, written to disk, shown on
 * the page and scored; past the budget they are dropped. The clock is passed in so tests can run it forward.
 */
public final class EventBudget {

    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last;
    private long dropped = 0;

    /**
     * @param perSecond how many events a second it keeps up with
     * @param burst     how many may come at once after a quiet spell
     */
    public EventBudget(double perSecond, double burst, long nowMillis) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
        this.last = nowMillis;
    }

    /** Whether one more event fits now; it is counted when it does */
    public synchronized boolean take(long nowMillis) {
        if (nowMillis > this.last) {
            this.tokens = Math.min(this.burst, this.tokens + (nowMillis - this.last) * this.perSecond / 1000.0);
            this.last = nowMillis;
        }
        if (this.tokens >= 1.0) {
            this.tokens -= 1.0;
            return true;
        }
        this.dropped += 1;
        return false;
    }

    /** How many events did not fit, all told */
    public synchronized long dropped() {
        return this.dropped;
    }

    /**
     * What is kept of the data a server sent with an event: its entries in order while they fit in so many characters
     * of JSON, and "data_cut": true when some did not. A packet can carry 32 KB, which no moment needs.
     */
    public static com.google.gson.JsonObject kept(com.google.gson.JsonObject data, int maxChars) {
        com.google.gson.JsonObject kept = new com.google.gson.JsonObject();
        int used = 2;
        boolean cut = false;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : data.entrySet()) {
            // The key, its quotes, the colon and the comma, then the value as JSON
            int size = entry.getKey().length() + 4 + entry.getValue().toString().length();
            if (used + size > maxChars) {
                cut = true;
                continue;
            }
            used += size;
            kept.add(entry.getKey(), entry.getValue());
        }
        if (cut) {
            kept.addProperty("data_cut", true);
        }
        return kept;
    }

}
