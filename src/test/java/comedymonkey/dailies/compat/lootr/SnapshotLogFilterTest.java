package comedymonkey.dailies.compat.lootr;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lootr's client-side error is left out only while a snapshot is taken, and nothing else ever is */
class SnapshotLogFilterTest {

    private static Log4jLogEvent event(String format, Object... arguments) {
        return Log4jLogEvent.newBuilder().setLoggerName(LootrCompat.LOGGER).setLevel(Level.ERROR)
            .setMessage(new ParameterizedMessage(format, arguments)).build();
    }

    @Test
    void lootrsClientSideErrorIsLeftOutOnlyDuringASnapshot() {
        AtomicBoolean taking = new AtomicBoolean(false);
        SnapshotLogFilter filter = new SnapshotLogFilter(LootrCompat.MESSAGE, taking::get);
        Log4jLogEvent lootr = event("Tried to fillUpdateTag on the client side for SimpleLootrInstance: {}", "instance@3f25a668");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(lootr), "outside a snapshot it is Lootr's to say");
        taking.set(true);
        assertEquals(Filter.Result.DENY, filter.filter(lootr));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("Unable to load loot table {}", "minecraft:chests/simple_dungeon")),
            "anything else Lootr says still reaches the log");
        assertEquals(1, filter.leftOut());
    }


    /** Added as LootrCompat adds it, to a logger named as Lootr's is: the message never reaches what writes the log */
    @Test
    void addedToTheLoggerItKeepsTheMessageFromTheLog() {
        java.util.List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.apache.logging.log4j.core.LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        org.apache.logging.log4j.core.Appender appender = new org.apache.logging.log4j.core.appender.AbstractAppender("dailies-test", null, null, true,
            org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                written.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger = context.getLogger(LootrCompat.LOGGER + ".test");
        logger.addAppender(appender);
        AtomicBoolean taking = new AtomicBoolean(true);
        logger.addFilter(new SnapshotLogFilter(LootrCompat.MESSAGE, taking::get));
        try {
            logger.error("Tried to fillUpdateTag on the client side for SimpleLootrInstance: {}", "a");
            logger.error("Something else went wrong: {}", "b");
            taking.set(false);
            logger.error("Tried to fillUpdateTag on the client side for SimpleLootrInstance: {}", "c");
        } finally {
            logger.removeAppender(appender);
        }
        assertEquals(java.util.List.of("Something else went wrong: b", "Tried to fillUpdateTag on the client side for SimpleLootrInstance: c"), written);
    }

}
