package comedymonkey.dailiescompat.create.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and sets the fields Create keeps to itself on its screens and value boxes, by name, as Create 6.0.10 names them.
 * Create's classes keep their own names in a built game, so a name found here stays found; when a later Create renames
 * one, the part using it fails, Dailies logs it once and leaves that part out, and the rest of the compat goes on.
 */
final class Reflect {

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** The field of this name on the object's class or the first class above it that has one */
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
            throw new IllegalStateException("Create's " + type.getName() + " has no field " + name);
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

    static int getInt(Object owner, String name) {
        try {
            return field(owner.getClass(), name).getInt(owner);
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

    static void setInt(Object owner, String name, int value) {
        try {
            field(owner.getClass(), name).setInt(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setBoolean(Object owner, String name, boolean value) {
        try {
            field(owner.getClass(), name).setBoolean(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

}
