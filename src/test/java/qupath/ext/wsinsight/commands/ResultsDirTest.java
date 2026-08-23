package qupath.ext.wsinsight.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The results folder is normally created per run, but naming an existing one
 * lets a command pick up where an earlier run left off.
 */
class ResultsDirTest {

    private static File fail() {
        throw new AssertionError("fallback should not be consulted");
    }

    @Test
    void blankFallsBackToTheGeneratedFolder(@TempDir Path tmp) {
        File generated = tmp.resolve("auto").toFile();

        assertSame(generated, GenericCommandDialog.resolveResultsDir(null, () -> generated));
        assertSame(generated, GenericCommandDialog.resolveResultsDir("", () -> generated));
        assertSame(generated, GenericCommandDialog.resolveResultsDir("   ", () -> generated));
    }

    @Test
    void anExistingFolderIsUsedAsIs(@TempDir Path tmp) throws Exception {
        File previous = tmp.resolve("run-20260101-000000").toFile();
        assertTrue(previous.mkdirs());

        File resolved = GenericCommandDialog.resolveResultsDir(
                previous.getAbsolutePath(), ResultsDirTest::fail);

        assertEquals(previous, resolved);
    }

    @Test
    void surroundingWhitespaceIsIgnored(@TempDir Path tmp) {
        File previous = tmp.resolve("padded").toFile();
        assertTrue(previous.mkdirs());

        assertEquals(previous, GenericCommandDialog.resolveResultsDir(
                "  " + previous.getAbsolutePath() + "  ", ResultsDirTest::fail));
    }

    @Test
    void aMissingFolderIsCreated(@TempDir Path tmp) {
        File fresh = tmp.resolve("nested/does/not/exist").toFile();

        File resolved = GenericCommandDialog.resolveResultsDir(
                fresh.getAbsolutePath(), ResultsDirTest::fail);

        assertEquals(fresh, resolved);
        assertTrue(fresh.isDirectory(), "the folder should have been created");
    }

    @Test
    void aPathThatIsAFileIsRejected(@TempDir Path tmp) throws Exception {
        File notADir = tmp.resolve("a-file.txt").toFile();
        assertTrue(notADir.createNewFile());

        assertNull(GenericCommandDialog.resolveResultsDir(
                notADir.getAbsolutePath(), ResultsDirTest::fail));
    }
}
