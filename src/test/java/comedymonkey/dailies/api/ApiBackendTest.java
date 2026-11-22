package comedymonkey.dailies.api;

import comedymonkey.dailies.api.internal.Backend;
import comedymonkey.dailies.core.ApiBackend;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The API reaches Dailies only through its Backend, so the API jar builds on its own */
class ApiBackendTest {

    @Test
    void theBackendIsDailiesOwnAndLoadedOnce() {
        assertInstanceOf(ApiBackend.class, Backend.get());
        assertSame(Backend.get(), Backend.get());
        assertEquals(ApiBackend.class.getName(), Backend.IMPLEMENTATION);
    }

    @Test
    void theApiImportsNothingOfDailiesOwn() throws IOException {
        Path api = Path.of("src/main/java/comedymonkey/dailies/api");
        List<String> strays;
        try (Stream<Path> files = Files.walk(api)) {
            strays = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> {
                        try {
                            return Files.readAllLines(file).stream()
                                    .filter(line -> line.startsWith("import comedymonkey.") && !line.startsWith("import comedymonkey.dailies.api."))
                                    .map(line -> api.relativize(file) + ": " + line);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }
        assertEquals(List.of(), strays);
    }

}
