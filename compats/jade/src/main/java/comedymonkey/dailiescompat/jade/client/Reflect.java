package comedymonkey.dailiescompat.jade.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and sets the fields Jade keeps to itself on its elements, its progress styles and its settings, by name, as Jade
 * 15.10.6 names them. Jade's classes keep their own names in a built game, so a name found here stays found; when a
 * later Jade renames one, the element using it is recorded as one the compat cannot draw, logged once, and the rest of
 * the box goes on.
 */
final class Reflect {

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** The field of this name on this class or the first class above it that has one */
    private static Field field(Class<?> type, String name) {
        return FIELDS.computeIfAbsent(type.getName() + "#" + name, key -> {
            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                try {
                    Field field = at.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException notHere) {
                    // Further up
                }
            }
            throw new IllegalStateException("Jade's " + type.getName() + " has no field " + name);
        });
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Object owner, String name) {
        try {
            return (T) field(owner.getClass(), name).get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A static field of this class (Jade keeps the line of a block being broken in them) */
    @SuppressWarnings("unchecked")
    static <T> T getStatic(Class<?> owner, String name) {
        try {
            return (T) field(owner, name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Object owner, String name, Object value) {
        try {
            field(owner.getClass(), name).set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

}
