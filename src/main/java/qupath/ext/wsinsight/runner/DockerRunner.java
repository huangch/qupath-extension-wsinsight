package qupath.ext.wsinsight.runner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.wsinsight.WSInsightSetup;

/**
 * Spawns the WSInsight Docker container, streams its output to a
 * {@link ProgressListener}, and supports cancellation via {@code docker kill}.
 * <p>
 * Thread model: {@link #run()} blocks the calling thread until the container
 * exits, so callers should typically invoke it from a background
 * {@code javafx.concurrent.Task}. {@link #cancel()} is safe to call from any
 * thread.
 */
public class DockerRunner {

    private static final Logger logger = LoggerFactory.getLogger(DockerRunner.class);

    /** Env vars whose value is a host path and must be rewritten for the container. */
    private static final Set<String> PATH_VALUED_ENV = Set.of(
            "WSINSIGHT_REMOTE_CACHE_DIR");

    /** Named volume backing $HF_HOME, so model weights survive between runs. */
    private static final String HF_CACHE_VOLUME = "wsinsight-hf-cache";
    private static final String HF_CACHE_MOUNT = "/app/hf-cache";

    private final String dockerBinary;
    private final String image;
    private final String gpus;
    private final String shmSize;
    private final List<PathMapper.Mount> mounts;
    private final Map<String, String> env;
    private final List<String> wsinsightArgs;

    private final Path cidFile;
    private volatile Process process;
    private volatile boolean cancelled;

    private DockerRunner(Builder b) {
        this.dockerBinary = b.dockerBinary;
        this.image = b.image;
        this.gpus = b.gpus;
        this.shmSize = b.shmSize;
        this.mounts = List.copyOf(b.mounts);
        this.env = new LinkedHashMap<>(b.env);
        this.wsinsightArgs = List.copyOf(b.wsinsightArgs);
        try {
            this.cidFile = Files.createTempFile("wsinsight-cid-" + UUID.randomUUID(), ".cid");
            // docker refuses to write the cidfile if it exists; delete it first.
            Files.deleteIfExists(this.cidFile);
        } catch (IOException e) {
            throw new RuntimeException("Unable to allocate cidfile for docker run", e);
        }
    }

    /** Build the {@code docker run ...} command line. */
    List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        // Reap dataloader workers; without it they survive `docker kill` as zombies.
        cmd.add("--init");
        cmd.add("--cidfile");
        cmd.add(cidFile.toString());
        if (gpus != null && !gpus.isBlank() && !"none".equalsIgnoreCase(gpus.trim())) {
            cmd.add("--gpus");
            cmd.add(gpus.trim());
        }
        if (shmSize != null && !shmSize.isBlank()) {
            cmd.add("--shm-size=" + shmSize.trim());
        }
        // Ask the entrypoint to remap its own user, rather than `--user`: the
        // latter makes it exec straight through without setting HOME, and a
        // host uid with no passwd entry then resolves Path.home() to "/".
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            String uid = System.getenv("UID");
            String gid = System.getenv("GID");
            if (uid == null || uid.isBlank()) uid = tryExec("id", "-u");
            if (gid == null || gid.isBlank()) gid = tryExec("id", "-g");
            if (uid != null && gid != null) {
                cmd.add("-e");
                cmd.add("HOST_UID=" + uid);
                cmd.add("-e");
                cmd.add("HOST_GID=" + gid);
            }
        }
        for (PathMapper.Mount m : mounts) {
            cmd.add("-v");
            cmd.add(m.dockerVolumeArg());
        }
        cmd.add("-v");
        cmd.add(HF_CACHE_VOLUME + ":" + HF_CACHE_MOUNT);
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            cmd.add("-e");
            cmd.add(e.getKey() + "=" + containerEnvValue(e.getKey(), e.getValue()));
        }
        cmd.add(image);
        cmd.add("wsinsight");
        cmd.addAll(wsinsightArgs);
        return cmd;
    }

    /** Rewrite host paths in path-valued env vars; other values pass through unchanged. */
    private String containerEnvValue(String key, String value) {
        if (!PATH_VALUED_ENV.contains(key)) return value;
        String mapped = new PathMapper(mounts).hostToContainer(value);
        if (mapped != null) return mapped;
        logger.warn("{}={} is not covered by any bind mount; passing it through unchanged. "
                + "It will only resolve if the same path exists inside the container.", key, value);
        return value;
    }

    private static String tryExec(String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line == null ? null : line.trim();
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /**
     * Check whether the Docker image is present locally.
     *
     * @return {@code true} if {@code docker image inspect <image>} exits 0,
     *         {@code false} otherwise (image missing, docker unreachable, etc.).
     */
    public static boolean imageExists(String dockerBinary, String image) {
        if (dockerBinary == null || dockerBinary.isBlank()
                || image == null || image.isBlank())
            return false;
        try {
            Process p = new ProcessBuilder(dockerBinary, "image", "inspect", image)
                    .redirectErrorStream(true)
                    .start();
            // Drain output to avoid pipe blocking on some docker versions.
            try (var in = p.getInputStream()) { in.readAllBytes(); }
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Run {@code docker pull <image>} and stream progress to the listener.
     *
     * @return exit code of {@code docker pull} (0 on success).
     */
    public static int pullImage(String dockerBinary, String image, ProgressListener listener)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(dockerBinary, "pull", image);
        if (listener != null) listener.onLogLine("$ " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        pumpOutput(p.getInputStream(), listener, line -> {});
        return p.waitFor();
    }

    /**
     * Probe the host's GPU visibility by running {@code nvidia-smi -L} inside
     * a disposable container of the given image. Returns the raw stdout
     * (one GPU per line) on success, or {@code null} if docker, the image,
     * or the NVIDIA container runtime are unavailable.
     */
    public static String detectGpus(String dockerBinary, String image) {
        if (dockerBinary == null || dockerBinary.isBlank()
                || image == null || image.isBlank())
            return null;
        try {
            Process p = new ProcessBuilder(
                    dockerBinary, "run", "--rm", "--gpus", "all",
                    "--entrypoint", "nvidia-smi", image, "-L")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            int rc = p.waitFor();
            return rc == 0 ? sb.toString().trim() : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Launch the container and block until it exits. Safe to invoke once. */
    public int run(ProgressListener listener) throws IOException, InterruptedException {
        List<String> cmd = buildCommand();
        logger.info("Launching WSInsight container: {}", String.join(" ", cmd));
        if (listener != null)
            listener.onLogLine("$ " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        this.process = pb.start();

        pumpOutput(process.getInputStream(), listener,
                line -> logger.info("wsinsight: {}", line));

        int exit = process.waitFor();
        if (cancelled)
            exit = 130; // conventional "cancelled by user" code
        try { Files.deleteIfExists(cidFile); } catch (IOException ignored) {}
        if (listener != null)
            listener.onFinished(exit);
        return exit;
    }

    /**
     * Stream process output, reproducing enough terminal behaviour for a tqdm
     * progress bar to redraw in place.
     * <p>
     * A lone carriage return rewrites the current line. Nested tqdm bars also
     * emit {@code ESC[A} (cursor up) immediately before a newline; that pair
     * leaves the cursor where it started, so it is consumed rather than
     * emulated. Every other escape sequence is discarded, since this output is
     * rendered as plain text.
     */
    static void pumpOutput(InputStream in, ProgressListener listener,
                           Consumer<String> log) throws IOException {
        StringBuilder buf = new StringBuilder();
        StringBuilder csi = new StringBuilder();
        Escape escape = Escape.NONE;
        boolean sawCR = false;
        int pendingCursorUp = 0;

        try (Reader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int c;
            while ((c = reader.read()) != -1) {
                switch (escape) {
                    case CSI -> {
                        csi.append((char) c);
                        if (c >= '@' && c <= '~') {          // final byte
                            if (c == 'A')
                                pendingCursorUp++;
                            escape = Escape.NONE;
                        }
                        continue;
                    }
                    case OSC -> {
                        if (c == 0x07)                        // BEL terminates
                            escape = Escape.NONE;
                        else if (c == 0x1B)
                            escape = Escape.OSC_ESC;
                        continue;
                    }
                    case OSC_ESC -> {
                        escape = (c == '\\') ? Escape.NONE : Escape.OSC;
                        continue;
                    }
                    case ESC -> {
                        if (c == '[') {
                            csi.setLength(0);
                            escape = Escape.CSI;
                        } else {
                            escape = (c == ']') ? Escape.OSC : Escape.NONE;
                        }
                        continue;
                    }
                    case NONE -> { }
                }

                if (c == 0x1B) {
                    escape = Escape.ESC;
                    continue;
                }

                if (sawCR) {
                    sawCR = false;
                    if (c == '\n') {                          // CRLF: one line
                        emitLine(buf, listener, log, pendingCursorUp);
                        pendingCursorUp = Math.max(0, pendingCursorUp - 1);
                        continue;
                    }
                    emitUpdate(buf, listener);                // lone CR redraws
                }

                if (c == '\r') {
                    sawCR = true;
                } else if (c == '\n') {
                    // After a cursor-up the newline only returns the cursor to
                    // the line it came from, so the text redraws in place.
                    emitLine(buf, listener, log, pendingCursorUp);
                    pendingCursorUp = Math.max(0, pendingCursorUp - 1);
                } else {
                    buf.append((char) c);
                }
            }
            if (sawCR)
                emitUpdate(buf, listener);
            if (buf.length() > 0)
                emitLine(buf, listener, log, 0);
        }
    }

    /** Escape-sequence parser state. */
    private enum Escape { NONE, ESC, CSI, OSC, OSC_ESC }

    private static void emitLine(StringBuilder buf, ProgressListener listener,
                                 Consumer<String> log, int pendingCursorUp) {
        if (pendingCursorUp > 0) {
            emitUpdate(buf, listener);
            return;
        }
        String line = buf.toString();
        buf.setLength(0);
        log.accept(line);
        if (listener != null)
            listener.onLogLine(line);
    }

    private static void emitUpdate(StringBuilder buf, ProgressListener listener) {
        String line = buf.toString();
        buf.setLength(0);
        if (!line.isEmpty() && listener != null)
            listener.onLogUpdate(line);
    }

    /** Kill the running container (if any) via {@code docker kill}. */
    public void cancel() {
        cancelled = true;
        String cid = readCid();
        if (cid != null && !cid.isBlank()) {
            try {
                new ProcessBuilder(dockerBinary, "kill", cid)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
            } catch (IOException | InterruptedException e) {
                logger.warn("Failed to docker kill {}: {}", cid, e.getMessage());
            }
        } else if (process != null) {
            process.destroy();
        }
    }

    private String readCid() {
        try {
            if (Files.exists(cidFile))
                return Files.readString(cidFile, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {}
        return null;
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder; all setters return {@code this}. */
    public static final class Builder {
        private String dockerBinary = "docker";
        private String image = "huangchtw/wsinsight:latest";
        private String gpus = "all";
        private String shmSize = "32g";
        private final List<PathMapper.Mount> mounts = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private final List<String> wsinsightArgs = new ArrayList<>();

        public Builder dockerBinary(String v) { this.dockerBinary = v; return this; }
        public Builder image(String v) { this.image = v; return this; }
        public Builder gpus(String v) { this.gpus = v; return this; }
        public Builder shmSize(String v) { this.shmSize = v; return this; }
        public Builder mount(PathMapper.Mount m) { this.mounts.add(m); return this; }
        public Builder mounts(List<PathMapper.Mount> ms) { this.mounts.addAll(ms); return this; }
        public Builder env(String k, String v) {
            if (v != null && !v.isEmpty()) this.env.put(k, v);
            return this;
        }
        public Builder args(List<String> a) { this.wsinsightArgs.addAll(a); return this; }
        public Builder arg(String a) { this.wsinsightArgs.add(a); return this; }
        public DockerRunner build() { return new DockerRunner(this); }

        /** Mounts accumulated so far, so callers translate arguments against the same set. */
        public List<PathMapper.Mount> getMounts() { return List.copyOf(mounts); }

        /** Pre-populate from {@link WSInsightSetup} (image, gpus, shm, env, mounts). */
        public Builder fromSetup(WSInsightSetup s) {
            dockerBinary(s.getDockerBinary());
            image(s.getDockerImage());
            gpus(s.getGpus());
            shmSize(s.getShmSize());
            for (String entry : s.getExtraMounts().split("[,;\\n]")) {
                String e = entry.trim();
                if (e.isEmpty()) continue;
                boolean ro = false;
                if (e.endsWith(":ro")) {
                    ro = true;
                    e = e.substring(0, e.length() - 3);
                } else if (e.endsWith(":rw")) {
                    e = e.substring(0, e.length() - 3);
                }
                int idx = e.lastIndexOf(':');
                if (idx <= 0) continue;
                mount(new PathMapper.Mount(new File(e.substring(0, idx)).toPath(),
                                           e.substring(idx + 1), ro));
            }
            // WSINSIGHT_ZOO_REGISTRY_PATH and KERAS_HOME are deliberately not set
            // here: the image points them at its bundled /app/zoo and /app/keras,
            // and the latter is where StarDist2D.from_pretrained finds its weights.
            env("S3_STORAGE_OPTIONS", s.getS3Options());
            env("WSINSIGHT_REMOTE_CACHE_DIR", s.getCacheDir());
            if (s.isExperimental()) env("WSINSIGHT_EXPERIMENTAL", "1");
            return this;
        }
    }
}
