package qupath.ext.wsinsight.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Running a host-installed wsinsight, with no container involved. */
@DisabledOnOs(OS.WINDOWS)
class NativeRunnerTest {

    /** A stand-in executable, so the tests need no real wsinsight. */
    private static Path script(Path dir, String name, String body) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        f.toFile().setExecutable(true);
        return f;
    }

    private static final class Recorder implements ProgressListener {
        final List<String> lines = new ArrayList<>();
        volatile Integer exit;

        @Override public void onLogLine(String line) { lines.add(line); }
        @Override public void onFinished(int exitCode) { exit = exitCode; }
        @Override public void onError(Throwable t) { }
    }

    @Test
    void argumentsAndExitCodeReachTheCaller(@TempDir Path dir) throws Exception {
        Path bin = script(dir, "fake-wsinsight", "echo \"got $*\"\nexit 3");
        Recorder rec = new Recorder();

        int exit = NativeRunner.builder()
                .binary(bin.toString())
                .args(List.of("run", "--batch-size", "8"))
                .build()
                .run(rec);

        assertEquals(3, exit);
        assertEquals(3, rec.exit);
        assertTrue(rec.lines.contains("got run --batch-size 8"), rec.lines.toString());
    }

    @Test
    void theCommandIsEchoedBeforeItRuns(@TempDir Path dir) throws Exception {
        Path bin = script(dir, "fake-wsinsight", "exit 0");
        Recorder rec = new Recorder();

        NativeRunner.builder().binary(bin.toString()).arg("patch").build().run(rec);

        assertEquals("$ " + bin + " patch", rec.lines.get(0));
    }

    @Test
    void theEnvironmentIsPassedThrough(@TempDir Path dir) throws Exception {
        Path bin = script(dir, "fake-wsinsight", "echo \"exp=$WSINSIGHT_EXPERIMENTAL\"");
        Recorder rec = new Recorder();

        NativeRunner.builder().binary(bin.toString())
                .env("WSINSIGHT_EXPERIMENTAL", "1").build().run(rec);

        assertTrue(rec.lines.contains("exp=1"), rec.lines.toString());
    }

    @Test
    void hostPathsAreNotRewritten(@TempDir Path dir) throws Exception {
        Path bin = script(dir, "fake-wsinsight", "echo \"got $*\"");
        Recorder rec = new Recorder();
        String hostPath = dir.resolve("slides").toString();

        NativeRunner.builder().binary(bin.toString())
                .args(List.of("run", "--wsi-dir", hostPath)).build().run(rec);

        assertTrue(rec.lines.contains("got run --wsi-dir " + hostPath), rec.lines.toString());
    }

    @Test
    void cancelStopsARunningCommand(@TempDir Path dir) throws Exception {
        Path bin = script(dir, "slow-wsinsight", "sleep 60");
        Recorder rec = new Recorder();
        NativeRunner runner = NativeRunner.builder().binary(bin.toString()).build();

        CountDownLatch started = new CountDownLatch(1);
        int[] exit = { -1 };
        Thread t = new Thread(() -> {
            try {
                started.countDown();
                exit[0] = runner.run(rec);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);   // let the process actually spawn
        runner.cancel();
        t.join(TimeUnit.SECONDS.toMillis(15));

        assertFalse(t.isAlive(), "cancel did not stop the run");
        assertEquals(130, exit[0], "cancelled runs report the conventional code");
    }

    @Test
    void availabilityIsProbedWithVersion(@TempDir Path dir) throws Exception {
        Path ok = script(dir, "good", "exit 0");
        Path bad = script(dir, "bad", "exit 1");

        assertTrue(NativeRunner.isAvailable(ok.toString()));
        assertFalse(NativeRunner.isAvailable(bad.toString()));
        assertFalse(NativeRunner.isAvailable(dir.resolve("missing").toString()));
        assertFalse(NativeRunner.isAvailable(""));
        assertFalse(NativeRunner.isAvailable(null));
    }

    @Test
    void bothRunnersShareTheSameInterface() {
        assertTrue(Set.of(DockerRunner.class.getInterfaces()).contains(Runner.class));
        assertTrue(Set.of(NativeRunner.class.getInterfaces()).contains(Runner.class));
    }
}
