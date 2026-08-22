package qupath.ext.wsinsight.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Line arithmetic behind the log window, driven by the events the runner emits.
 */
class LogDocumentTest {

    /** Applies edits to a plain string, exactly as the TextArea would. */
    private static final class Screen {
        private final LogDocument doc = new LogDocument();
        private String text = "";

        void appendLine(String line) { apply(doc.appendLine(line, text.length())); }

        void updateLine(String line) { apply(doc.updateLine(line, text.length())); }

        private void apply(LogDocument.Edit edit) {
            text = text.substring(0, edit.start()) + edit.text();
        }
    }

    @Test
    void completedLinesStack() {
        Screen s = new Screen();
        s.appendLine("one");
        s.appendLine("two");
        assertEquals("one\ntwo\n", s.text);
    }

    @Test
    void redrawsReplaceTheSameLine() {
        Screen s = new Screen();
        s.appendLine("start");
        s.updateLine(" 10%");
        s.updateLine(" 50%");
        s.updateLine("100%");
        assertEquals("start\n100%", s.text);
    }

    @Test
    void closingBarOverwritesItsOwnRedraw() {
        // tqdm ends a bar with '\r<final state>\n', which the runner reports
        // as an update followed by a line. Both are the same screen line.
        Screen s = new Screen();
        s.updateLine("Slides:   0%|          | 0/1");
        s.appendLine("Slides: 100%|##########| 1/1");
        assertEquals("Slides: 100%|##########| 1/1\n", s.text);
    }

    @Test
    void aNewLineAfterARedrawStartsBelowIt() {
        Screen s = new Screen();
        s.updateLine("bar 50%");
        s.appendLine("bar 100%");
        s.appendLine("next");
        assertEquals("bar 100%\nnext\n", s.text);
    }

    @Test
    void redrawAfterACompletedLineOpensANewOne() {
        Screen s = new Screen();
        s.appendLine("done");
        s.updateLine("working");
        s.updateLine("working.");
        assertEquals("done\nworking.", s.text);
    }
}
