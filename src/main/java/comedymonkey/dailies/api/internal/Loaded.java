package comedymonkey.dailies.api.internal;

/** Holds the {@link Backend}, loaded by name the first time it is asked for */
final class Loaded {

    static final Backend BACKEND = load();

    private Loaded() {
    }

    private static Backend load() {
        try {
            return (Backend) Class.forName(Backend.IMPLEMENTATION, true, Backend.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Dailies' API was called, but Dailies' own " + Backend.IMPLEMENTATION + " could not be loaded", e);
        }
    }

}
