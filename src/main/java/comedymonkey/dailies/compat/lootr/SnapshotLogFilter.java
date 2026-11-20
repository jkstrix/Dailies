package comedymonkey.dailies.compat.lootr;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.function.BooleanSupplier;

/**
 * Leaves out of the log one message another mod writes each time a snapshot asks one of its block entities what it
 * would send of itself, while that snapshot is being taken, and lets everything else through. Needs no game.
 */
final class SnapshotLogFilter extends AbstractFilter {

    private final String start;
    private final BooleanSupplier taking;
    private volatile long leftOut = 0;

    SnapshotLogFilter(String start, BooleanSupplier taking) {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
        this.start = start;
        this.taking = taking;
    }

    @Override
    public Result filter(LogEvent event) {
        Message message = event.getMessage();
        String format = message == null ? null : message.getFormat();
        if (format != null && format.startsWith(this.start) && this.taking.getAsBoolean()) {
            this.leftOut++;
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    /** How many messages have been left out since the game started */
    long leftOut() {
        return this.leftOut;
    }

}
