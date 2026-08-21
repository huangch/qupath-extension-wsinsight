package qupath.ext.wsinsight.runner;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        b.env("KERAS_HOME", "/home/somebody/.keras");
        List<String> cmd = b.build().buildCommand();

        assertEquals("/home/somebody/.keras", envValue(cmd, "KERAS_HOME"));
    }

    @Test
    void zooRegistryEnvIsNeverSet(@TempDir Path dir) {
        // The image points WSINSIGHT_ZOO_REGISTRY_PATH at its bundled /app/zoo;
        // overriding it from QuPath breaks model resolution in the container.
        DockerRunner.Builder b = DockerRunner.builder();
        b.mount(new PathMapper.Mount(dir, "/slides"));
        List<String> cmd = b.build().buildCommand();

        assertEquals(null, envValue(cmd, "WSINSIGHT_ZOO_REGISTRY_PATH"));
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
