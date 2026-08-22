package qupath.ext.wsinsight.ui;

/**
 * Tracks where the in-place (carriage-return) line starts, and turns log
 * events into a single text replacement.
 * <p>
 * Kept free of JavaFX so the line arithmetic can be tested directly.
 */
final class LogDocument {

    /** A replacement of everything from {@code start} to the end of the text. */
    record Edit(int start, String text) { }

    /** Offset where the in-place line starts, or -1 when none is open. */
    private int pendingStart = -1;

    /**
     * Finish the current line. A line that was being redrawn is overwritten
     * rather than appended to: tqdm ends a bar with a second carriage return
     * and its final state, which a terminal draws over the previous one.
     */
    Edit appendLine(String line, int length) {
        int start = pendingStart >= 0 ? pendingStart : length;
        pendingStart = -1;
        return new Edit(start, line + "\n");
    }

    /** Redraw the current line from its first column. */
    Edit updateLine(String line, int length) {
        if (pendingStart < 0)
            pendingStart = length;
        return new Edit(pendingStart, line);
    }
}
