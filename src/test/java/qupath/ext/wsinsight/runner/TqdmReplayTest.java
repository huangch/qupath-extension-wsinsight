package qupath.ext.wsinsight.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Replays bytes captured from a real nested tqdm run, so the log window shows
 * one line per bar instead of one line per repaint.
 */
class TqdmReplayTest {

    /** Verbatim stderr from tqdm with an outer and an inner bar, piped. */
    private static final String CAPTURED =
            "\rImages:   0%|          | 0/2 [00:00<?, ?it/s]\n"
            + "\rInference:   0%|          | 0/3 [00:00<?, ?it/s]\u001B[A\n"
            + "\r                                                \u001B[A\n"
            + "\rInference:   0%|          | 0/3 [00:00<?, ?it/s]\u001B[A\n"
            + "\r                                                \u001B[A"
            + "\rImages: 100%|##########| 2/2 [00:00<00:00, 6949.97it/s]\n";

    @Test
    void everyRepaintStaysOnOneLine() throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        List<String> logged = new ArrayList<>();

        DockerRunner.pumpOutput(
                new ByteArrayInputStream(CAPTURED.getBytes(StandardCharsets.UTF_8)),
                new ProgressListener() {
                    @Override public void onLogLine(String line) { lines.add(line); }
                    @Override public void onLogUpdate(String line) { updates.add(line); }
                    @Override public void onFinished(int exitCode) { }
                    @Override public void onError(Throwable t) { }
                },
                logged::add);

        // The outer bar's first paint is the only completed line.
        assertEquals(List.of("Images:   0%|          | 0/2 [00:00<?, ?it/s]"), lines);
        assertEquals(lines, logged);

        // Everything else redraws in place rather than adding lines.
        assertTrue(updates.size() >= 4, "expected in-place repaints, got " + updates);
        assertTrue(updates.get(updates.size() - 1).startsWith("Images: 100%"),
                "last repaint should be the finished bar: " + updates);

        // No escape sequence survives into the rendered text.
        for (String s : updates)
            assertTrue(s.indexOf('\u001B') < 0, "escape leaked: " + s);
    }
}
