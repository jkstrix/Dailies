package comedymonkey.dailiescompat.supplementaries.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and sets the fields the mods (and the game) keep to themselves on their screens and widgets, and calls the few
 * methods they keep to themselves, by name, as Supplementaries 4.0.2, Amendments 2.2.7 and the game (with NeoForge's
 * names, which a built game keeps) name them. When a later version renames one, the part using it fails, Dailies logs it
 * once and leaves that part out, and the rest of the compat goes on.
 */
final class Reflect {

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** The field of this name on the class or the first class above it that has one */
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
            throw new IllegalStateException(type.getName() + " has no field " + name);
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

    /** A static field of this class: the mods keep much of their client state in them */
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

    static void setStatic(Class<?> owner, String name, Object value) {
        try {
            field(owner, name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A method of this class (or the first class above it that has one) with these parameter types, by name */
    static Method method(Class<?> type, String name, Class<?>... parameters) {
        return METHODS.computeIfAbsent(type.getName() + "#" + name + Arrays.toString(parameters), key -> {
            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                try {
                    Method method = at.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException notHere) {
                    // Further up
                }
            }
            throw new IllegalStateException(type.getName() + " has no method " + name);
        });
    }

    /** Calls a method of the object's class with these parameter types, by name */
    @SuppressWarnings("unchecked")
    static <T> T call(Object owner, String name, Class<?>[] parameters, Object... arguments) {
        try {
            return (T) method(owner.getClass(), name, parameters).invoke(owner, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls a static method of this class with these parameter types, by name */
    @SuppressWarnings("unchecked")
    static <T> T callStatic(Class<?> owner, String name, Class<?>[] parameters, Object... arguments) {
        try {
            return (T) method(owner, name, parameters).invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
