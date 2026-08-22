package qupath.ext.wsinsight.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Carriage returns must redraw the current line instead of starting a new one,
 * so a tqdm progress bar does not fill the log with one line per tick.
 */
class DockerRunnerOutputTest {

    /** Records callbacks as {@code "line:..."} / {@code "update:..."}. */
    private static final class Recorder implements ProgressListener {
        final List<String> events = new ArrayList<>();

        @Override public void onLogLine(String line) { events.add("line:" + line); }
        @Override public void onLogUpdate(String line) { events.add("update:" + line); }
        @Override public void onFinished(int exitCode) { }
        @Override public void onError(Throwable t) { }
    }

    private static List<String> pump(String raw) throws IOException {
        return pump(raw, new ArrayList<>());
    }

    /** Runs the pump, collecting what would reach the slf4j log in {@code logged}. */
    private static List<String> pump(String raw, List<String> logged) throws IOException {
        Recorder rec = new Recorder();
        DockerRunner.pumpOutput(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),
                rec,
                logged::add);
        return rec.events;
    }

    @Test
    void newlineEndsALine() throws IOException {
        assertEquals(List.of("line:hello", "line:world"), pump("hello\nworld\n"));
    }

    @Test
    void carriageReturnRedrawsTheSameLine() throws IOException {
        // What tqdm emits: repeated redraws, then a final newline.
        assertEquals(
                List.of("update: 10%", "update: 50%", "line: 100%"),
                pump(" 10%\r 50%\r 100%\n"));
    }

    @Test
    void crlfIsASingleLineBreak() throws IOException {
        assertEquals(List.of("line:one", "line:two"), pump("one\r\ntwo\r\n"));
    }

    @Test
    void trailingTextWithoutNewlineIsStillEmitted() throws IOException {
        assertEquals(List.of("line:done"), pump("done"));
    }

    @Test
    void trailingCarriageReturnEmitsAnUpdate() throws IOException {
        assertEquals(List.of("update:99%"), pump("99%\r"));
    }

    @Test
    void emptyRedrawsAreNotReported() throws IOException {
        assertEquals(List.of("line:x"), pump("\r\rx\n"));
    }

    @Test
    void escapeSequencesReachNeitherTheLogNorTheListener() throws IOException {
        String esc = "\u001B";
        List<String> logged = new ArrayList<>();
        List<String> events = pump(esc + "[32mok" + esc + "[0m\n" + esc + "[31m 50%\r", logged);

        assertEquals(List.of("ok"), logged);
        assertEquals(List.of("line:ok", "update: 50%"), events);
    }
}
