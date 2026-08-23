package qupath.ext.wsinsight.runner;

import java.io.IOException;

/**
 * A way of executing one WSInsight command and streaming its output.
 * <p>
 * Implemented by {@link DockerRunner}, which runs the published image, and by
 * {@link NativeRunner}, which runs a {@code wsinsight} already installed on the
 * host.
 */
public interface Runner {

    /** Launch the command and block until it exits. */
    int run(ProgressListener listener) throws IOException, InterruptedException;

    /** Stop a running command; safe to call when nothing is running. */
    void cancel();
}
