package qupath.ext.wsinsight.ui;

import javafx.scene.control.TextArea;

/**
 * Read-only log view that understands carriage returns, so a progress bar
 * redraws in place instead of adding one line per tick.
 */
public class LogArea extends TextArea {

    /** Offset where the in-place line starts, or -1 when none is open. */
    private int pendingStart = -1;

    public LogArea() {
        setEditable(false);
        // The launch command is one very long line; wrap rather than scroll
        // sideways.
        setWrapText(true);
        // "Monospaced" is a JavaFX logical font, so it resolves on every
        // platform; setting only the family keeps the inherited font size.
        setStyle("-fx-font-family: 'Monospaced';");
    }

    /** Append a completed line. */
    public void appendLine(String line) {
        closePending();
        appendText(line + "\n");
    }

    /** Redraw the line currently being written (carriage return). */
    public void updateLine(String line) {
        if (pendingStart < 0) {
            pendingStart = getLength();
            appendText(line);
        } else {
            replaceText(pendingStart, getLength(), line);
            positionCaret(getLength());
        }
    }

    /** Leave the last drawn state of an in-place line on screen. */
    private void closePending() {
        if (pendingStart >= 0) {
            appendText("\n");
            pendingStart = -1;
        }
    }
}
