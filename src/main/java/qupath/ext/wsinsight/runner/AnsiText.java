package qupath.ext.wsinsight.runner;

import java.util.regex.Pattern;

/**
 * Removal of ANSI escape sequences from container output.
 * <p>
 * The container is not attached to a terminal, so colour and cursor codes
 * would otherwise appear as literal text in the log window.
 */
final class AnsiText {

    /** CSI sequences (colour, cursor, erase) and OSC sequences (window title). */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]"
                    + "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    private AnsiText() {}

    /** Strip escape sequences, leaving the visible text. */
    static String strip(String line) {
        return line == null || line.indexOf('\u001B') < 0
                ? line
                : ANSI.matcher(line).replaceAll("");
    }
}
