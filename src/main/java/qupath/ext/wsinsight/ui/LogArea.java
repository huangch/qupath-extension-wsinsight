package qupath.ext.wsinsight.ui;

import javafx.scene.control.TextArea;

/**
 * Read-only log view that understands carriage returns, so a progress bar
 * redraws in place instead of adding one line per tick.
 */
public class LogArea extends TextArea {

    private final LogDocument document = new LogDocument();

    public LogArea() {
        setEditable(false);
        // The launch command is one very long line; wrap rather than scroll
        // sideways.
        setWrapText(true);
        // "Monospaced" is a JavaFX logical font, so it resolves on every
        // platform; setting only the family keeps the inherited font size.
        setStyle("-fx-font-family: \'Monospaced\';");
    }

    /** Append a completed line. */
    public void appendLine(String line) {
        apply(document.appendLine(line, getLength()));
    }

    /** Redraw the line currently being written (carriage return). */
    public void updateLine(String line) {
        apply(document.updateLine(line, getLength()));
    }

    private void apply(LogDocument.Edit edit) {
        replaceText(edit.start(), getLength(), edit.text());
        positionCaret(getLength());
    }
}
