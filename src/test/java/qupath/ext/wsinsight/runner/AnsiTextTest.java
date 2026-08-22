package qupath.ext.wsinsight.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Container output is plain text, so escape sequences must not reach the
 * log file or the log window.
 */
class AnsiTextTest {

    private static final String ESC = "\u001B";

    @Test
    void plainTextIsUntouched() {
        assertEquals("hello world", AnsiText.strip("hello world"));
    }

    @Test
    void colourCodesAreRemoved() {
        assertEquals("done", AnsiText.strip(ESC + "[32mdone" + ESC + "[0m"));
    }

    @Test
    void tqdmStyleBarKeepsItsText() {
        String raw = ESC + "[A" + ESC + "[2K 45%|####      | 45/100";
        assertEquals(" 45%|####      | 45/100", AnsiText.strip(raw));
    }

    @Test
    void cursorMovesAndEraseCodesAreRemoved() {
        assertEquals("x", AnsiText.strip(ESC + "[1;31m" + ESC + "[Kx" + ESC + "[m"));
    }

    @Test
    void operatingSystemCommandsAreRemoved() {
        assertEquals("after", AnsiText.strip(ESC + "]0;window title\u0007after"));
    }

    @Test
    void nullIsPassedThrough() {
        assertEquals(null, AnsiText.strip(null));
    }
}
