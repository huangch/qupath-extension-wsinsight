package qupath.ext.wsinsight.runner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.wsinsight.WSInsightSetup;

/**
 * Runs a {@code wsinsight} installed on the host, with no container involved.
 * <p>
 * Paths are passed through unchanged, so any file the user can read is usable
 * without configuring bind mounts. GPU visibility, shared memory and the model
 * cache are whatever the host environment already provides.
 */
public final class NativeRunner implements Runner {

    private static final Logger logger = LoggerFactory.getLogger(NativeRunner.class);

    private final String binary;
    private final Map<String, String> env;
    private final List<String> args;

    private volatile Process process;
    private volatile boolean cancelled;

    private NativeRunner(Builder b) {
        this.binary = b.binary;
        this.env = Map.copyOf(b.env);
        this.args = List.copyOf(b.args);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether the configured executable can be found and reports a version. */
    public static boolean isAvailable(String binary) {
        if (binary == null || binary.isBlank())
            return false;
        try {
            Process p = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public int run(ProgressListener listener) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.addAll(args);
        logger.info("Launching native wsinsight: {}", String.join(" ", cmd));
        if (listener != null)
            listener.onLogLine("$ " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(env);
        process = pb.start();

        DockerRunner.pumpOutput(process.getInputStream(), listener,
                line -> logger.info("wsinsight: {}", line));

        int exit = process.waitFor();
        if (cancelled)
            exit = 130; // conventional "cancelled by user" code
        if (listener != null)
            listener.onFinished(exit);
        return exit;
    }

    @Override
    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p == null)
            return;
        // Children first: wsinsight spawns dataloader workers that would
        // otherwise keep running after the parent is gone.
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    public static final class Builder {
        private String binary = "wsinsight";
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> args = new ArrayList<>();

        public Builder binary(String v) {
            if (v != null && !v.isBlank()) this.binary = v;
            return this;
        }

        public Builder env(String k, String v) {
            if (v != null && !v.isEmpty()) this.env.put(k, v);
            return this;
        }

        public Builder args(List<String> a) { this.args.addAll(a); return this; }

        public Builder arg(String a) { this.args.add(a); return this; }

        /** Pre-populate the environment from {@link WSInsightSetup}. */
        public Builder fromSetup(WSInsightSetup s) {
            binary(s.getNativeBinary());
            // Blank preferences are dropped by env(), so the process keeps
            // whatever the environment that launched QuPath already exported.
            env("WSINSIGHT_ZOO_REGISTRY_PATH", s.getZooRegistryPath());
            env("KERAS_HOME", s.getKerasHome());
            env("HF_HOME", s.getHfHome());
            if (s.isHfTransfer()) env("HF_HUB_ENABLE_HF_TRANSFER", "1");
            env("S3_STORAGE_OPTIONS", s.getS3Options());
            env("WSINSIGHT_REMOTE_CACHE_DIR", s.getCacheDir());
            if (s.isExperimental()) env("WSINSIGHT_EXPERIMENTAL", "1");
            return this;
        }

        public NativeRunner build() { return new NativeRunner(this); }
    }
}
