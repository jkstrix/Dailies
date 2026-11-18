package comedymonkey.dailies.library;

import comedymonkey.dailies.file.Spans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What has been exported (REC-8), written by the game being played and by the background export game alike */
class ExportLogTest {

    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path folder;

    private static List<String> recordings(ExportLog log) {
        return log.all().stream().map(exported -> exported.recordingId).sorted().toList();
    }

    @Test
    void neitherGameUndoesTheOthersExports() throws IOException {
        List<Spans.Span> map = List.of(new Spans.Span("a", 0, 1000, 0));
        // The game being played has read the list once and keeps it
        ExportLog game = new ExportLog(this.folder);
        assertTrue(game.all().isEmpty());
        // The worker, a second game, notes an export
        ExportLog worker = new ExportLog(this.folder);
        worker.add("from-worker", "Kingdom", "exports/one.mp4", 10, NOW, map, 0, 100);
        // The game being played sees it, and adding its own keeps it
        assertEquals(List.of("from-worker"), recordings(game));
        game.add("from-game", "Kingdom", "exports/two.mp4", 20, NOW + 1, map, 100, 200);
        assertEquals(List.of("from-game", "from-worker"), recordings(game));
        // And the worker sees the game's
        worker.add("from-worker-again", "Kingdom", "exports/three.mp4", 30, NOW + 2, map, 200, 300);
        assertEquals(List.of("from-game", "from-worker", "from-worker-again"), recordings(worker));
        assertEquals(List.of("from-game", "from-worker", "from-worker-again"), recordings(new ExportLog(this.folder)));
    }

}
