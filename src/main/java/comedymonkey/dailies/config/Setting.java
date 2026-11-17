package comedymonkey.dailies.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One value of the settings file, with what is needed to describe it and to change it from outside the settings
 * screen. Reading is safe from any thread and before the file has loaded: the default is returned then.
 */
public final class Setting<T> {

    public enum Kind {
        BOOLEAN, INTEGER, DECIMAL, TEXT, CHOICE
    }

    private final String name;
    private final String group;
    private final Kind kind;
    private final T fallback;
    private final String comment;
    private final @Nullable Number min;
    private final @Nullable Number max;
    private final ModConfigSpec.ConfigValue<T> value;

    Setting(String name, String group, Kind kind, T fallback, String comment, @Nullable Number min, @Nullable Number max, ModConfigSpec.ConfigValue<T> value) {
        this.name = name;
        this.group = group;
        this.kind = kind;
        this.fallback = fallback;
        this.comment = comment;
        this.min = min;
        this.max = max;
        this.value = value;
    }

    public String name() {
        return this.name;
    }

    public String group() {
        return this.group;
    }

    public Kind kind() {
        return this.kind;
    }

    public T fallback() {
        return this.fallback;
    }

    public String comment() {
        return this.comment;
    }

    public @Nullable Number min() {
        return this.min;
    }

    public @Nullable Number max() {
        return this.max;
    }

    public T get() {
        if (!DailiesConfig.loaded()) {
            return this.fallback;
        }
        try {
            return this.value.get();
        } catch (RuntimeException e) {
            return this.fallback;
        }
    }

    /** The names a choice setting accepts, or null for the other kinds */
    public @Nullable String[] choices() {
        if (this.kind != Kind.CHOICE) {
            return null;
        }
        Object[] constants = this.fallback.getClass().getEnumConstants();
        String[] names = new String[constants.length];
        for (int i = 0; i < constants.length; i++) {
            names[i] = ((Enum<?>) constants[i]).name();
        }
        return names;
    }

    /**
     * Change the value from something loosely typed (a JSON value, a string from a tool). Does not save the file.
     *
     * @throws IllegalArgumentException with a message a person can act on when the value does not fit
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setFrom(Object raw) {
        Object converted = switch (this.kind) {
            case BOOLEAN -> {
                if (raw instanceof Boolean flag) {
                    yield flag;
                }
                String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                if (text.equals("true") || text.equals("on")) {
                    yield Boolean.TRUE;
                }
                if (text.equals("false") || text.equals("off")) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("'" + this.name + "' must be true or false");
            }
            case INTEGER -> {
                double number = this.toNumber(raw);
                if (number != Math.rint(number)) {
                    throw new IllegalArgumentException("'" + this.name + "' must be a whole number; the nearest is " + (long) Math.rint(number));
                }
                this.checkRange(number);
                yield (int) number;
            }
            case DECIMAL -> {
                double number = this.toNumber(raw);
                this.checkRange(number);
                yield number;
            }
            case TEXT -> raw == null ? "" : String.valueOf(raw);
            case CHOICE -> {
                String wanted = String.valueOf(raw).trim();
                for (Object constant : this.fallback.getClass().getEnumConstants()) {
                    if (((Enum) constant).name().equalsIgnoreCase(wanted)) {
                        yield constant;
                    }
                }
                throw new IllegalArgumentException("'" + this.name + "' must be one of " + String.join(", ", this.choices()));
            }
        };
        this.value.set((T) converted);
    }

    private double toNumber(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + this.name + "' must be a number");
        }
    }

    private void checkRange(double number) {
        if (Double.isNaN(number)) {
            throw new IllegalArgumentException("'" + this.name + "' must be a number");
        }
        if (this.min != null && number < this.min.doubleValue()) {
            throw new IllegalArgumentException("'" + this.name + "' must be at least " + this.min + "; the nearest allowed value is " + this.min);
        }
        if (this.max != null && number > this.max.doubleValue()) {
            throw new IllegalArgumentException("'" + this.name + "' must be at most " + this.max + "; the nearest allowed value is " + this.max);
        }
    }

}
