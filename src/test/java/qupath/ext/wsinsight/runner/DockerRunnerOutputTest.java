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
    private static final String ESC = "\u001B";

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
        List<String> logged = new ArrayList<>();
        List<String> events = pump(ESC + "[32mok" + ESC + "[0m\n" + ESC + "[31m 50%\r", logged);

        assertEquals(List.of("ok"), logged);
        assertEquals(List.of("line:ok", "update: 50%"), events);
    }

    @Test
    void operatingSystemCommandsAreDiscarded() throws IOException {
        assertEquals(List.of("line:after"), pump(ESC + "]0;window title\u0007after\n"));
    }

    @Test
    void nestedTqdmBarsRedrawOneLine() throws IOException {
        // Exactly what tqdm writes for an inner bar: cursor up, newline, CR.
        String raw = "\rOuter:   0%\n"
                + "\rInner:   0%" + ESC + "[A\n"
                + "\rInner:  50%" + ESC + "[A\n"
                + "\rInner: 100%" + ESC + "[A\n";

        List<String> logged = new ArrayList<>();
        List<String> events = pump(raw, logged);

        // The outer bar is a real line; the inner bar redraws in place.
        assertEquals(
                List.of("line:Outer:   0%", "update:Inner:   0%",
                        "update:Inner:  50%", "update:Inner: 100%"),
                events);
        // Only completed lines reach the log file, not every repaint.
        assertEquals(List.of("Outer:   0%"), logged);
    }

    @Test
    void cursorUpWithoutNewlineStillRedrawsInPlace() throws IOException {
        assertEquals(
                List.of("update:Outer: 100%"),
                pump(ESC + "[A\rOuter: 100%\n"));
    }
}
