package qupath.ext.wsinsight;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.wsinsight.commands.LastUsedValues;
import qupath.ext.wsinsight.commands.ProjectLastUsedValues;
import qupath.ext.wsinsight.commands.SharedResultsDir;
import qupath.ext.wsinsight.commands.WSInsightCommands;
import qupath.fx.dialogs.Dialogs;
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
        prefs.addPropertyPreference(s.useNativeProperty(), Boolean.class,
                "Use native wsinsight", "wsinsight",
                "Run a wsinsight installed on this machine instead of the Docker "
                        + "image. Paths are passed through unchanged, so no bind "
                        + "mounts are involved.");
        prefs.addPropertyPreference(s.nativeBinaryProperty(), String.class,
                "Native wsinsight binary", "wsinsight",
                "Executable used when 'Use native wsinsight' is on "
                        + "(default 'wsinsight', resolved on PATH).");
        prefs.addChoicePropertyPreference(s.wsiBackendProperty(),
                javafx.collections.FXCollections.observableArrayList(
                        WSInsightSetup.WSI_BACKEND_AUTO, "openslide", "tiffslide"),
                String.class,
                "WSI backend", "wsinsight",
                "Library used to read slides, passed as `wsinsight --backend`. "
                        + "'auto' lets wsinsight pick whichever is installed.");
        prefs.addPropertyPreference(s.cliSchemaPathProperty(), String.class,
                "CLI schema path", "wsinsight",
                "Path to the schema written by `wsinsight schema --output <path>`. "
                        + "Regenerate it after changing the CLI or the model zoo, then "
                        + "use Extensions \u2192 wsinsight \u2192 Reload CLI schema.");
        prefs.addPropertyPreference(s.useLocalModelsProperty(), Boolean.class,
                "Use local model files", "wsinsight",
                "On: pass --zoo-model-dir using the path wsinsight reported for the "
                        + "model, so nothing is downloaded. Off: pass --model, which "
                        + "always fetches from HuggingFace and needs outbound HTTPS.");
        prefs.addPropertyPreference(s.exportGeoJsonProperty(), Boolean.class,
                "Export GeoJSON detections", "wsinsight",
                "Initial state of the GeoJSON export checkbox in every wsinsight "
                        + "dialog. GeoJSON is the only format this extension can import, "
                        + "so turning it off makes a run produce nothing QuPath displays. "
                        + "Can still be overridden per run in the dialog.");
        prefs.addPropertyPreference(s.autoImportProperty(), Boolean.class,
                "Import results when a run finishes", "wsinsight",
                "On: import the GeoJSON detections into the project as soon as a run "
                        + "exits successfully, instead of waiting for Extensions \u2192 "
                        + "wsinsight \u2192 Import results. Requires 'Export GeoJSON "
                        + "detections', and is skipped for runs where that checkbox was "
                        + "cleared. Leave off when chaining several steps and importing "
                        + "once at the end.");
        prefs.addPropertyPreference(s.overwriteProperty(), Boolean.class,
                "Overwrite existing results", "wsinsight",
                "Initial state of the --overwrite checkbox. On: re-running into a "
                        + "results directory recomputes slides that already have "
                        + "outputs, which is what a changed model or parameter needs. "
                        + "Off: those slides are skipped, so an import may load the "
                        + "previous run's detections. Can be overridden per run.");
        prefs.addPropertyPreference(s.s3OptionsProperty(), String.class,
                "S3 storage options (JSON)", "wsinsight",
                "Value passed as S3_STORAGE_OPTIONS inside the container.");
        prefs.addPropertyPreference(s.cacheDirProperty(), String.class,
                "Remote cache directory", "wsinsight",
                "Host directory used to cache slides streamed from S3/GDC. In Docker "
                        + "mode it must sit under the slides or results directory, "
                        + "otherwise the container cannot see it.");
        prefs.addPropertyPreference(s.zooRegistryPathProperty(), String.class,
                "Model zoo registry", "wsinsight",
                "Path to wsinsight-zoo-registry.json (WSINSIGHT_ZOO_REGISTRY_PATH). "
                        + "Leave blank to use the Docker image's bundled registry, or "
                        + "whatever the environment that launched QuPath provides for a "
                        + "native run. Set it to avoid needing a wrapper script. In "
                        + "Docker mode the path must sit under the slides or results "
                        + "directory, otherwise the container cannot see it.");
        prefs.addPropertyPreference(s.kerasHomeProperty(), String.class,
                "Keras home", "wsinsight",
                "KERAS_HOME, where StarDist2D.from_pretrained looks for its weights. "
                        + "Same blank-means-inherit and Docker visibility rules as the "
                        + "model zoo registry.");
        prefs.addPropertyPreference(s.hfHomeProperty(), String.class,
                "Hugging Face cache", "wsinsight",
                "HF_HOME, where model weights downloaded from Hugging Face are kept. "
                        + "Same blank-means-inherit and Docker visibility rules as the "
                        + "model zoo registry.");
        prefs.addPropertyPreference(s.hfTransferProperty(), Boolean.class,
                "Fast Hugging Face downloads", "wsinsight",
                "Set HF_HUB_ENABLE_HF_TRANSFER=1, which needs the hf_transfer package "
                        + "installed. Off leaves the variable alone.");
        prefs.addPropertyPreference(s.experimentalProperty(), Boolean.class,
                "Enable experimental features", "wsinsight",
                "When enabled, WSINSIGHT_EXPERIMENTAL=1 is set inside the container, "
                        + "unhiding experimental subcommands (hplot, hplot-finalize, niche, "
                        + "niche-profile, ecomp, tcomp, agg, import) and the --hplot / --niche "
                        + "flags on run.");
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

    /**
     * Subcommands hidden unless the 'Enable experimental features' preference is on.
     * Must match wsinsight.cli.cli._EXPERIMENTAL_COMMANDS: a command the CLI hides
     * but this set omits is launched without WSINSIGHT_EXPERIMENTAL and dies as an
     * unknown subcommand.
     */
    private static final java.util.Set<String> EXPERIMENTAL_COMMANDS = java.util.Set.of(
            "hplot", "hplot-finalize", "niche", "niche-profile",
            "ecomp", "tcomp", "agg", "import");

    private void addMenuItems(QuPathGUI qupath) {
        Menu menu = qupath.getMenu(MENU_NAME, true);
        menu.getItems().clear();
        WSInsightSetup setup = WSInsightSetup.getInstance();

        java.util.Map<String, java.util.function.Supplier<
                qupath.ext.wsinsight.commands.GenericCommandDialog>> commands;
        try {
            commands = WSInsightCommands.all();
        } catch (java.io.IOException e) {
            logger.error("WSInsight CLI schema unavailable", e);
            MenuItem problem = item("Schema not loaded — click for details",
                    () -> Dialogs.showErrorMessage(
                            "WSInsight CLI schema", e.getMessage()));
            menu.getItems().add(problem);
            menu.getItems().add(reloadSchemaItem(qupath));
            return;
        }

        for (var entry : commands.entrySet()) {
            final var factory = entry.getValue();
            MenuItem mi = item(labelFor(entry.getKey()), () -> factory.get().showAndRun());
            if (EXPERIMENTAL_COMMANDS.contains(entry.getKey())) {
                // Bind visibility so toggling the pref shows/hides the item live.
                mi.visibleProperty().bind(setup.experimentalProperty());
            }
            menu.getItems().add(mi);
        }
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        menu.getItems().add(item("Import results\u2026",
                () -> new qupath.ext.wsinsight.commands.ImportResultsDialog(qupath).showAndImport()));
        menu.getItems().add(reloadSchemaItem(qupath));
    }

    /** Re-read the schema file so a regenerated one takes effect without restarting. */
    private MenuItem reloadSchemaItem(QuPathGUI qupath) {
        return item("Reload CLI schema", () -> {
            WSInsightCommands.reset();
            addMenuItems(qupath);
            try {
                var s = WSInsightCommands.schema();
                Dialogs.showInfoNotification("WSInsight",
                        "Reloaded CLI schema (wsinsight " + s.wsinsightVersion() + "), "
                        + s.models().size() + " model(s).");
            } catch (java.io.IOException e) {
                Dialogs.showErrorMessage("WSInsight CLI schema", e.getMessage());
                return;
            }
            offerToResetRememberedValues(qupath);
        });
    }

    /**
     * Remembered values take priority over the schema, so a reload alone can
     * leave dialogs showing parameters from the previous CLI version. Clearing
     * them is offered here rather than done automatically: the values are the
     * user's own input, and a reload is usually about picking up a new model.
     */
    private void offerToResetRememberedValues(QuPathGUI qupath) {
        // Fully-qualified names would resolve against the `qupath` parameter,
        // not the package, so these are imported at the top of the file.
        var project = qupath == null ? null : qupath.getProject();
        boolean scoped = ProjectLastUsedValues.hasProject(project);
        boolean reset = Dialogs.showYesNoDialog("WSInsight",
                "Also reset remembered parameters?\n\n"
                        + "Dialogs currently reopen with the values you last used"
                        + (scoped ? " in this project" : "")
                        + ", which override the wsinsight defaults in the schema "
                        + "you just reloaded.\n\n"
                        + "Yes \u2014 forget them and start from the new defaults.\n"
                        + "No \u2014 keep them (the schema is reloaded either way).");
        if (!reset) return;

        int cleared = ProjectLastUsedValues.clearAll(project) + LastUsedValues.clearAll();
        SharedResultsDir.clearProject(project);
        Dialogs.showInfoNotification("WSInsight",
                cleared == 0
                        ? "No remembered parameters to reset."
                        : "Reset remembered parameters for " + cleared + " dialog(s).");
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
            java.util.Map.entry("niche",          "Niche discovery\u2026"),
            java.util.Map.entry("niche-profile",  "Niche profile\u2026"),
            java.util.Map.entry("agg",            "Cell-type aggregates\u2026"),
            java.util.Map.entry("import",         "Import spatial transcriptomics\u2026"),
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
