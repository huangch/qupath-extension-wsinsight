package qupath.ext.wsinsight;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.wsinsight.commands.WSInsightCommands;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.panes.PreferencePane;

/**
 * Entry point for the WSInsight QuPath extension.
 * <p>
 * Registers persistent preferences in the "WSInsight" preference category and
 * populates the {@code Extensions > WSInsight} menu with one entry per
 * WSInsight CLI subcommand. Each menu item launches the same
 * {@link qupath.ext.wsinsight.ui.WSInsightProgressDialog} after collecting
 * arguments through a generated form.
 */
public class WSInsightExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(WSInsightExtension.class);
    private static final String MENU_NAME = "Extensions>wsinsight";
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;
        logger.info("Installing WSInsight extension v0.1.0");

        registerPreferences(qupath);
        addMenuItems(qupath);
        probeGpusAsync(WSInsightSetup.getInstance());
    }

    private void registerPreferences(QuPathGUI qupath) {
        WSInsightSetup s = WSInsightSetup.getInstance();
        PreferencePane prefs = qupath.getPreferencePane();

        prefs.addPropertyPreference(s.dockerBinaryProperty(), String.class,
                "Docker binary", "wsinsight",
                "Path to the docker executable (default 'docker').");
        prefs.addPropertyPreference(s.dockerImageProperty(), String.class,
                "Docker image", "wsinsight",
                "WSInsight Docker image tag (e.g. huangchtw/wsinsight:latest).");
        prefs.addPropertyPreference(s.gpusProperty(), String.class,
                "GPUs", "wsinsight",
                "Value for docker --gpus (e.g. 'all', 'none', 'device=0', 'device=0,1').");
        prefs.addPropertyPreference(s.shmSizeProperty(), String.class,
                "Shared memory size", "wsinsight",
                "Value for docker --shm-size. Use '32g' for multi-worker dataloaders.");
        prefs.addPropertyPreference(s.extraMountsProperty(), String.class,
                "Extra mounts", "wsinsight",
                "Additional bind mounts, separated by commas/semicolons/newlines. "
                        + "Format: 'host/path:/container/path'.");
        prefs.addPropertyPreference(s.zooRegistryProperty(), String.class,
                "WSInsight zoo registry path", "wsinsight",
                "Value passed as WSINSIGHT_ZOO_REGISTRY_PATH inside the container.");
        prefs.addPropertyPreference(s.s3OptionsProperty(), String.class,
                "S3 storage options (JSON)", "wsinsight",
                "Value passed as S3_STORAGE_OPTIONS inside the container.");
        prefs.addPropertyPreference(s.cacheDirProperty(), String.class,
                "Remote cache directory", "wsinsight",
                "Value passed as WSINSIGHT_REMOTE_CACHE_DIR inside the container.");
        prefs.addPropertyPreference(s.kerasHomeProperty(), String.class,
                "KERAS_HOME", "wsinsight",
                "Override Keras config/weights directory inside the container.");
        prefs.addPropertyPreference(s.autoImportResultsProperty(), Boolean.class,
                "Auto-import results", "wsinsight",
                "Import GeoJSON annotations and OME-CSV measurements back into the "
                        + "active QuPath project when a job finishes successfully.");
        prefs.addPropertyPreference(s.experimentalProperty(), Boolean.class,
                "Enable experimental features", "wsinsight",
                "When enabled, WSINSIGHT_EXPERIMENTAL=1 is set inside the container, "
                        + "unhiding experimental subcommands (hplot, hplot-finalize, cme, ecomp, tcomp) "
                        + "and the --hplot / --cme flags on run.");
        prefs.addPropertyPreference(s.gpusDetectedProperty(), String.class,
                "Detected GPUs", "wsinsight",
                "Cached output of `nvidia-smi -L` run inside the container; refreshed "
                        + "automatically on extension startup when the image is present.");
    }

    /**
     * Probe the host's visible GPUs by running {@code nvidia-smi -L} inside
     * the configured image and cache the result in the "Detected GPUs"
     * preference. Runs on a daemon thread so it never blocks startup.
     */
    private void probeGpusAsync(WSInsightSetup s) {
        Thread t = new Thread(() -> {
            try {
                if (!qupath.ext.wsinsight.runner.DockerRunner
                        .imageExists(s.getDockerBinary(), s.getDockerImage()))
                    return;
                String out = qupath.ext.wsinsight.runner.DockerRunner
                        .detectGpus(s.getDockerBinary(), s.getDockerImage());
                if (out != null && !out.isBlank()) {
                    javafx.application.Platform.runLater(() -> s.setGpusDetected(out));
                }
            } catch (Exception e) {
                logger.debug("GPU probe failed: {}", e.toString());
            }
        }, "wsinsight-gpu-probe");
        t.setDaemon(true);
        t.start();
    }

    /** Subcommands hidden unless the 'Enable experimental features' preference is on. */
    private static final java.util.Set<String> EXPERIMENTAL_COMMANDS = java.util.Set.of(
            "hplot", "hplot-finalize", "cme", "ecomp", "tcomp");

    private void addMenuItems(QuPathGUI qupath) {
        Menu menu = qupath.getMenu(MENU_NAME, true);
        var commands = WSInsightCommands.all();
        WSInsightSetup setup = WSInsightSetup.getInstance();
        for (var entry : commands.entrySet()) {
            final var factory = entry.getValue();
            MenuItem mi = item(labelFor(entry.getKey()), () -> factory.get().showAndRun());
            if (EXPERIMENTAL_COMMANDS.contains(entry.getKey())) {
                // Bind visibility so toggling the pref shows/hides the item live.
                mi.visibleProperty().bind(setup.experimentalProperty());
            }
            menu.getItems().add(mi);
        }
    }

    /** Friendly menu label for each subcommand. Falls back to capitalised name. */
    private static final java.util.Map<String, String> COMMAND_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("run",            "Run inference\u2026"),
            java.util.Map.entry("patch",          "Patch extraction\u2026"),
            java.util.Map.entry("infer",          "Inference\u2026"),
            java.util.Map.entry("reg",            "Region registration\u2026"),
            java.util.Map.entry("ncomp",          "Neighborhood composition\u2026"),
            java.util.Map.entry("ecomp",          "Edge composition\u2026"),
            java.util.Map.entry("tcomp",          "Triad composition\u2026"),
            java.util.Map.entry("cme",            "Cellular microenvironment\u2026"),
            java.util.Map.entry("cme-profile",    "Cellular microenvironment profile\u2026"),
            java.util.Map.entry("hplot",          "H-Plot analysis\u2026"),
            java.util.Map.entry("hplot-finalize", "H-Plot finalize\u2026"),
            java.util.Map.entry("export",         "Export results\u2026"));

    private static String labelFor(String commandName) {
        String custom = COMMAND_LABELS.get(commandName);
        if (custom != null) return custom;
        if (commandName.isEmpty()) return commandName + "\u2026";
        return Character.toUpperCase(commandName.charAt(0)) + commandName.substring(1) + "\u2026";
    }

    private static MenuItem item(String label, Runnable r) {
        MenuItem mi = new MenuItem(label);
        mi.setOnAction(ev -> {
            try {
                r.run();
            } catch (Exception ex) {
                LoggerFactory.getLogger(WSInsightExtension.class)
                        .error("WSInsight command failed", ex);
                javafx.scene.control.Alert a =
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                a.setHeaderText("WSInsight command failed");
                a.setContentText(ex.getMessage());
                a.showAndWait();
            }
        });
        return mi;
    }

    @Override public String getName() { return "wsinsight"; }

    @Override public String getDescription() {
        return "QuPath GUI wrapper around the WSInsight Docker image for whole-slide "
                + "patch-level classification, single-cell inference, and graph-based "
                + "spatial analytics.";
    }

    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }

    @Override public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "huangch", "qupath-extension-wsinsight");
    }
}
