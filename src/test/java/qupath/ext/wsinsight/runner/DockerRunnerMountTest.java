package qupath.ext.wsinsight.runner;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host paths in the launch command only resolve inside the container when a
 * mount covers them; these pin down that translation.
 */
public class DockerRunnerMountTest {

    private static String envValue(List<String> cmd, String key) {
        for (int i = 0; i < cmd.size() - 1; i++) {
            if ("-e".equals(cmd.get(i)) && cmd.get(i + 1).startsWith(key + "="))
                return cmd.get(i + 1).substring(key.length() + 1);
        }
        return null;
    }

    @Test
    void pathValuedEnvIsRewrittenToContainerPath(@TempDir Path dir) {
        DockerRunner.Builder b = DockerRunner.builder();
        b.mount(new PathMapper.Mount(dir, "/cache"));
        b.env("WSINSIGHT_REMOTE_CACHE_DIR", dir.resolve("slides").toString());
        List<String> cmd = b.build().buildCommand();

        assertEquals("/cache/slides", envValue(cmd, "WSINSIGHT_REMOTE_CACHE_DIR"));
    }

    @Test
    void nonPathEnvIsLeftAlone(@TempDir Path dir) {
        DockerRunner.Builder b = DockerRunner.builder();
        b.mount(new PathMapper.Mount(dir, "/cache"));
        b.env("S3_STORAGE_OPTIONS", "key=/not/a/host/path");
        List<String> cmd = b.build().buildCommand();

        assertEquals("key=/not/a/host/path", envValue(cmd, "S3_STORAGE_OPTIONS"));
    }

    @Test
    void unmountedPathEnvPassesThroughUnchanged() {
        DockerRunner.Builder b = DockerRunner.builder();
        b.env("WSINSIGHT_REMOTE_CACHE_DIR", "/home/somebody/cache");
        List<String> cmd = b.build().buildCommand();

        assertEquals("/home/somebody/cache", envValue(cmd, "WSINSIGHT_REMOTE_CACHE_DIR"));
    }

    @Test
    void imageOwnedEnvVarsAreNeverOverridden(@TempDir Path dir) {
        // The image points these at /app/zoo and /app/keras; overriding either
        // with a host path breaks model lookup and StarDist2D.from_pretrained.
        DockerRunner.Builder b = DockerRunner.builder();
        b.mount(new PathMapper.Mount(dir, "/slides"));
        List<String> cmd = b.build().buildCommand();

        assertEquals(null, envValue(cmd, "WSINSIGHT_ZOO_REGISTRY_PATH"));
        assertEquals(null, envValue(cmd, "KERAS_HOME"));
    }

    @Test
    void identityIsPassedAsHostUidNotUserFlag() {
        // `--user` makes docker-entrypoint.sh exec through without exporting
        // HOME, so Path.home() becomes "/" for uids with no passwd entry.
        List<String> cmd = DockerRunner.builder().build().buildCommand();

        assertFalse(cmd.contains("--user"), "must not bypass the entrypoint's remapping");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            assertNotNull(envValue(cmd, "HOST_UID"), "HOST_UID must be passed");
            assertNotNull(envValue(cmd, "HOST_GID"), "HOST_GID must be passed");
        }
    }

    @Test
    void hfCacheVolumeAndInitMatchTheReferenceScript() {
        List<String> cmd = DockerRunner.builder().build().buildCommand();

        assertTrue(cmd.contains("--init"), "dataloader workers must be reaped");
        assertTrue(cmd.contains("wsinsight-hf-cache:/app/hf-cache"),
                "weights would be re-downloaded on every run without this");
    }

    @Test
    void writableMountHasNoReadOnlySuffix(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath().normalize() + ":/results",
                new PathMapper.Mount(dir, "/results").dockerVolumeArg());
        assertEquals(dir.toAbsolutePath().normalize() + ":/results:ro",
                new PathMapper.Mount(dir, "/results", true).dockerVolumeArg());
    }

    @Test
    void getMountsSeesEveryMountUsedForTranslation(@TempDir Path dir) {
        DockerRunner.Builder b = DockerRunner.builder();
        b.mount(new PathMapper.Mount(dir, "/slides"));

        PathMapper pm = new PathMapper(b.getMounts());
        assertEquals("/slides/case1.svs", pm.hostToContainer(dir.resolve("case1.svs").toString()));
    }
}
