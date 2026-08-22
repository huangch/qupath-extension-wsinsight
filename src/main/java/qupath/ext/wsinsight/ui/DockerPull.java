package qupath.ext.wsinsight.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.wsinsight.runner.DockerRunner;
import qupath.ext.wsinsight.runner.ProgressListener;

/**
 * Minimal modal dialog that streams {@code docker pull} output to a text area
 * and returns the exit code. Used by the pre-flight image check in
 * {@code GenericCommandDialog} so a missing image surfaces as a clear
 * prompt rather than a cryptic {@code docker run} failure.
 */
public final class DockerPull {

    private static final Logger logger = LoggerFactory.getLogger(DockerPull.class);

    private DockerPull() {}

    /**
     * Show a modal "Pulling &lt;image&gt;…" dialog and block until
     * {@code docker pull} finishes. Must be called on the JavaFX Application Thread.
     *
     * @return exit code (0 = success), or -1 on I/O error.
     */
    public static int runWithProgress(String dockerBinary, String image) {
        Stage stage = new Stage();
        stage.setTitle("WSInsight — pulling " + image);
        stage.initModality(Modality.APPLICATION_MODAL);

        LogArea log = new LogArea();
        log.setPrefColumnCount(100);
        log.setPrefRowCount(16);

        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(Double.MAX_VALUE);
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Label status = new Label("Pulling " + image + "…");
        VBox bottom = new VBox(6, bar, status);
        bottom.setPadding(new Insets(8));

        BorderPane root = new BorderPane(log);
        root.setBottom(bottom);
        root.setPadding(new Insets(8));
        stage.setScene(new Scene(root, 760, 420));

        int[] result = { -1 };
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return DockerRunner.pullImage(dockerBinary, image, new ProgressListener() {
                    @Override public void onLogLine(String line) {
                        Platform.runLater(() -> log.appendLine(line));
                    }
                    @Override public void onLogUpdate(String line) {
                        Platform.runLater(() -> log.updateLine(line));
                    }
                    @Override public void onFinished(int code) { /* handled below */ }
                    @Override public void onError(Throwable t) {
                        Platform.runLater(() -> log.appendLine("ERROR: " + t));
                    }
                });
            }
        };
        task.setOnSucceeded(ev -> {
            result[0] = task.getValue();
            status.setText(result[0] == 0 ? "Finished." : "Failed (exit " + result[0] + ").");
            bar.setProgress(result[0] == 0 ? 1.0 : 0.0);
            stage.close();
        });
        task.setOnFailed(ev -> {
            Throwable t = task.getException();
            logger.warn("docker pull failed", t);
            log.appendText("ERROR: " + (t == null ? "unknown" : t.toString()) + "\n");
            status.setText("Failed.");
            bar.setProgress(0.0);
            result[0] = -1;
            stage.close();
        });

        Thread thread = new Thread(task, "wsinsight-docker-pull");
        thread.setDaemon(true);
        thread.start();
        stage.showAndWait();
        return result[0];
    }
}
