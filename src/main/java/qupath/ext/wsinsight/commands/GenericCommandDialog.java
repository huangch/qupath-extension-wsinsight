package qupath.ext.wsinsight.commands;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ButtonType;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Callback;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import qupath.ext.wsinsight.WSInsightSetup;
import qupath.ext.wsinsight.runner.DockerRunner;
import qupath.ext.wsinsight.ui.DockerPull;
import qupath.ext.wsinsight.ui.WSInsightProgressDialog;

/**
 * Generic form for a single WSInsight subcommand. Collects user input for a
 * list of {@link ParamSpec}s, translates paths via the active
 * {@link qupath.ext.wsinsight.runner.PathMapper}, and launches the Docker
 * container through {@link WSInsightProgressDialog}.
 */
public class GenericCommandDialog {

    /**
     * CLI flags that the extension supplies automatically from the chosen
     * slide scope and the scratch results directory. They are hidden from the
     * generated form because inside the Docker container they always resolve
     * to the fixed bind-mount targets {@code /slides} and {@code /results}.
     */
    private static final Map<String, String> AUTO_PATH_FLAGS = Map.of(
            "--wsi-dir", "/slides",
            "--results-dir", "/results");

    private final String title;
    private final String subcommand;
    private final List<ParamSpec> specs;
    private final Map<String, SchemaLoader.GroupSpec> groupDefs;
    private final java.util.Set<String> autoFlagsForCommand;
    /**
     * Zoo registry entries available for this dialog's {@code --model} picker,
     * keyed by their display label. Populated at {@link #showAndRun()} start
     * when {@code WSINSIGHT_ZOO_REGISTRY_PATH} is configured and readable.
     * Empty → fall back to the schema's hard-coded {@code --model} choices.
     */
    private final Map<String, ZooRegistry.Entry> zooByLabel = new LinkedHashMap<>();

    public GenericCommandDialog(String title, String subcommand, List<ParamSpec> specs) {
        this(title, subcommand, specs, Map.of());
    }

    public GenericCommandDialog(String title, String subcommand, List<ParamSpec> specs,
                                Map<String, SchemaLoader.GroupSpec> groupDefs) {
        this.title = title;
        this.subcommand = subcommand;
        // Drop experimental groups when the pref is off (mirrors menu gating).
        boolean experimental = WSInsightSetup.getInstance().isExperimental();
        Map<String, SchemaLoader.GroupSpec> filteredGroups = groupDefs == null
                ? Map.of() : new LinkedHashMap<>(groupDefs);
        if (!experimental) {
            filteredGroups.keySet().removeAll(EXPERIMENTAL_GROUPS);
        }
        this.groupDefs = filteredGroups;
        // Strip auto-supplied path options from the dialog; they are appended
        // to argv after the user clicks OK using the scope-derived slides
        // mount root and the scratch results dir (which map to /slides and
        // /results inside the container). Also hide experimental flags from
        // the main grid unless the experimental pref is on.
        List<ParamSpec> visible = new ArrayList<>(specs.size());
        java.util.Set<String> auto = new java.util.HashSet<>();
        for (ParamSpec s : specs) {
            if (s.flag != null && AUTO_PATH_FLAGS.containsKey(s.flag)) {
                auto.add(s.flag);
                continue;
            }
            if (!experimental) {
                // Hide experimental top-level flags and every param living
                // inside an experimental group (otherwise they would fall
                // through into the main grid since their group key is no
                // longer registered).
                if (s.flag != null && EXPERIMENTAL_FLAGS.contains(s.flag)) continue;
                if (s.group != null && EXPERIMENTAL_GROUPS.contains(s.group)) continue;
            }
            visible.add(s);
        }
        this.specs = visible;
        this.autoFlagsForCommand = auto;
    }

    /** Experimental {@code run}-dialog flags hidden when the pref is off. */
    private static final java.util.Set<String> EXPERIMENTAL_FLAGS =
            java.util.Set.of("--hplot", "--niche", "--ecomp", "--tcomp",
                    "--agg", "--import");
    /** Experimental group keys hidden when the pref is off. */
    private static final java.util.Set<String> EXPERIMENTAL_GROUPS =
            java.util.Set.of("hplot_tuning", "niche_tuning", "ecomp_tuning", "tcomp_tuning");

    /** Show the parameter form; on OK, launch the container and block until it finishes. */
    public void showAndRun() {
        WSInsightSetup setupPre = WSInsightSetup.getInstance();
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();

        // Load the zoo registry (if configured) so the --model dropdown can
        // show human-readable labels and emit -z <local-model-dir> instead
        // of -m <name> on submit. An empty map preserves the legacy behaviour
        // (hard-coded choices from the schema, emitted as -m <name>).
        zooByLabel.clear();
        for (ZooRegistry.Entry e : ZooRegistry.load(setupPre.getZooRegistry())) {
            // Guard against duplicate labels; first wins.
            zooByLabel.putIfAbsent(e.displayLabel, e);
        }

        // Previously-saved user input for this subcommand (per-flag values).
        // Used further down to seed widgets after they're built and to
        // pre-populate per-group sub-dialog state so re-opening a sub-dialog
        // restores the user's last choices.
        Map<String, String> lastUsed = LastUsedValues.load(subcommand);

        // --- Scope availability ------------------------------------------
        RunScope currentScope = RunScope.fromCurrentImage(gui);
        Project<?> project = gui != null ? gui.getProject() : null;
        boolean haveProject = project != null && !project.getImageList().isEmpty();

        if (currentScope == null && !haveProject) {
            Dialogs.showErrorMessage("wsinsight",
                    "No image available. Open a slide or open a project with images.");
            return;
        }

        // --- Results dir --------------------------------------------------
        java.io.File resultsDir = resolveHostResultsRoot(setupPre, project);
        if (resultsDir == null) {
            Dialogs.showErrorMessage("wsinsight",
                    "Could not create a results directory.");
            return;
        }

        // --- Build dialog -------------------------------------------------
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("wsinsight — " + subcommand);

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Scope selector row lives in its own VBox above the two-column body.
        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton rbCurrent = new RadioButton("Current image");
        RadioButton rbAll = new RadioButton("All project images");
        RadioButton rbSelection = new RadioButton("Selected project images…");
        rbCurrent.setToggleGroup(scopeGroup);
        rbAll.setToggleGroup(scopeGroup);
        rbSelection.setToggleGroup(scopeGroup);
        rbCurrent.setDisable(currentScope == null);
        rbAll.setDisable(!haveProject);
        rbSelection.setDisable(!haveProject);
        final List<ProjectImageEntry<?>> pickedEntries = new ArrayList<>();
        rbSelection.setOnAction(ev -> {
            if (project == null) return;
            List<ProjectImageEntry<?>> picked = pickProjectEntries(project, pickedEntries);
            if (picked == null) {
                if (!rbCurrent.isDisabled()) rbCurrent.setSelected(true);
                else if (!rbAll.isDisabled()) rbAll.setSelected(true);
                return;
            }
            pickedEntries.clear();
            pickedEntries.addAll(picked);
            rbSelection.setText("Selected project images… (" + picked.size() + ")");
        });
        if (!rbCurrent.isDisabled()) rbCurrent.setSelected(true);
        else if (!rbAll.isDisabled()) rbAll.setSelected(true);

        VBox scopeBox = new VBox(4,
                new Label("Process:"),
                new HBox(12, rbCurrent, rbAll, rbSelection));
        scopeBox.setPadding(new Insets(8, 8, 8, 8));

        Map<String, Node> inputs = new LinkedHashMap<>();
        Map<String, Map<String, String>> groupValues = new LinkedHashMap<>();
        List<ParamSpec> mainSpecs = new ArrayList<>();
        LinkedHashMap<String, List<ParamSpec>> byGroup = new LinkedHashMap<>();
        for (ParamSpec s : specs) {
            if (s.group != null && groupDefs.containsKey(s.group)) {
                byGroup.computeIfAbsent(s.group, k -> new ArrayList<>()).add(s);
            } else {
                mainSpecs.add(s);
            }
        }

        // Seed dialog-group sub-dialog state from the last-used snapshot so
        // re-opening a sub-dialog shows the user's previous entries.
        for (Map.Entry<String, List<ParamSpec>> ge : byGroup.entrySet()) {
            Map<String, String> seed = new LinkedHashMap<>();
            for (ParamSpec ps : ge.getValue()) {
                String k = keyFor(ps);
                String v = lastUsed.get(k);
                if (v != null && !v.isEmpty()) seed.put(k, v);
            }
            if (!seed.isEmpty()) groupValues.put(ge.getKey(), seed);
        }

        // Compute "anchored" groups: dialog-render groups whose visible_when
        // points at a BOOL_FLAG. These render next to their gating checkbox.
        Map<String, ParamSpec> mainByFlag = new java.util.HashMap<>();
        for (ParamSpec s : mainSpecs) {
            if (s.flag != null) mainByFlag.put(s.flag, s);
        }
        Map<String, SchemaLoader.GroupSpec> anchorByFlag = new java.util.HashMap<>();
        for (SchemaLoader.GroupSpec g : groupDefs.values()) {
            if (g.render != SchemaLoader.GroupSpec.Render.DIALOG) continue;
            if (g.visibleWhen == null || g.visibleWhen.flag == null) continue;
            ParamSpec anchorSpec = mainByFlag.get(g.visibleWhen.flag);
            if (anchorSpec != null && anchorSpec.kind == ParamSpec.Kind.BOOL_FLAG)
                anchorByFlag.put(g.visibleWhen.flag, g);
        }

        // Two-column body: each column is its own VBox so rows size
        // independently (no reserved-grid whitespace when columns differ in
        // height). Column boundary is taken from the first main param marked
        // {@code "column_break": true}; falls back to floor(total/2).
        int total = mainSpecs.size();
        int leftCount = -1;
        for (int i = 0; i < total; i++) {
            if (mainSpecs.get(i).columnBreak) { leftCount = i; break; }
        }
        if (leftCount < 0) leftCount = total / 2;

        VBox leftCol = new VBox(6);
        VBox rightCol = new VBox(6);

        for (int i = 0; i < total; i++) {
            ParamSpec spec = mainSpecs.get(i);
            Label label = new Label(spec.label + (spec.required ? " *" : "") + ":");
            label.setMinWidth(180);
            label.setPrefWidth(180);
            if (!spec.help.isBlank()) label.setTooltip(new Tooltip(spec.help));
            Node input = buildInput(spec);

            // If a dialog-group is anchored to this checkbox, place its button
            // (disabled until the checkbox is ticked) right next to the input.
            SchemaLoader.GroupSpec anchor =
                    spec.flag != null ? anchorByFlag.get(spec.flag) : null;
            Node cell;
            if (anchor != null && input instanceof CheckBox cb) {
                Button btn = makeGroupButton(anchor, byGroup.get(anchor.key), groupValues);
                btn.disableProperty().bind(cb.selectedProperty().not());
                HBox box = new HBox(8, cb, btn);
                HBox.setHgrow(btn, javafx.scene.layout.Priority.ALWAYS);
                cell = box;
            } else {
                HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
                cell = input;
            }
            HBox row = new HBox(8, label, cell);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (i < leftCount) leftCol.getChildren().add(row);
            else rightCol.getChildren().add(row);
            inputs.put(keyFor(spec), input);
        }

        // Group rows (dialog-render + inline) are appended to the LEFT column
        // so they sit directly under the left-column main params. VBox
        // respects managed=false cleanly; hidden groups collapse with no gap.
        for (Map.Entry<String, List<ParamSpec>> ge : byGroup.entrySet()) {
            SchemaLoader.GroupSpec g = groupDefs.get(ge.getKey());
            List<ParamSpec> groupParams = ge.getValue();
            if (g.render == SchemaLoader.GroupSpec.Render.DIALOG
                    && g.visibleWhen != null
                    && anchorByFlag.get(g.visibleWhen.flag) == g) {
                continue; // already anchored in the main grid
            }

            if (g.render == SchemaLoader.GroupSpec.Render.DIALOG) {
                Button btn = makeGroupButton(g, groupParams, groupValues);
                Label groupLabel = new Label(g.title + ":");
                groupLabel.setMinWidth(180);
                groupLabel.setPrefWidth(180);
                HBox row = new HBox(8, groupLabel, btn);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                leftCol.getChildren().add(row);
                bindVisibility(row, g.visibleWhen, inputs);
            } else {
                Label header = new Label(g.title);
                header.setStyle("-fx-font-weight: bold;");
                leftCol.getChildren().add(header);
                bindVisibility(header, g.visibleWhen, inputs);
                for (ParamSpec spec : groupParams) {
                    Label lbl = new Label(spec.label + (spec.required ? " *" : "") + ":");
                    lbl.setMinWidth(180);
                    lbl.setPrefWidth(180);
                    if (!spec.help.isBlank()) lbl.setTooltip(new Tooltip(spec.help));
                    Node input = buildInput(spec);
                    HBox row = new HBox(8, lbl, input);
                    HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
                    row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    leftCol.getChildren().add(row);
                    inputs.put(keyFor(spec), input);
                    bindVisibility(row, g.visibleWhen, inputs);
                }
            }
        }

        javafx.scene.layout.Region gutter = new javafx.scene.layout.Region();
        gutter.setMinWidth(20);
        HBox body = new HBox(leftCol, gutter, rightCol);
        body.setPadding(new Insets(8));
        body.setAlignment(javafx.geometry.Pos.TOP_LEFT);

        // Seed all main-grid and inline-group widgets with the user's
        // previously saved values (if any). Done after every widget is
        // registered in `inputs` so that both main and inline specs benefit.
        for (Map.Entry<String, Node> en : inputs.entrySet()) {
            String saved = lastUsed.get(en.getKey());
            if (saved != null && !saved.isEmpty()) writeInput(en.getValue(), saved);
        }

        // Required-field validation: disable OK until every required spec
        // (whose enclosing group is currently visible) has a non-blank value.
        // Dialog-group specs are resolved via `groupValues` since their
        // widgets only exist inside sub-dialogs.
        Button okButton = (Button) pane.lookupButton(ButtonType.OK);
        Runnable revalidate = () -> {
            boolean ok = true;
            for (ParamSpec s : specs) {
                if (!s.required) continue;
                // Skip if the enclosing group is currently hidden.
                if (s.group != null) {
                    SchemaLoader.GroupSpec gs = groupDefs.get(s.group);
                    if (gs != null && gs.visibleWhen != null) {
                        String srcVal = valueFor(gs.visibleWhen.flag, inputs, Map.of());
                        if (!gs.visibleWhen.test(srcVal)) continue;
                    }
                }
                String v;
                Node n = inputs.get(keyFor(s));
                if (n != null) v = readInput(n);
                else {
                    Map<String, String> sub = s.group == null
                            ? null : groupValues.get(s.group);
                    v = sub == null ? null : sub.get(keyFor(s));
                    if (v == null) v = s.defaultValue;
                }
                if (v == null || v.isBlank()) { ok = false; break; }
            }
            if (okButton != null) okButton.setDisable(!ok);
        };
        // Listen on every main/inline widget so typing a value re-enables OK.
        for (Node n : inputs.values()) attachChangeListener(n, revalidate);
        // Re-evaluate after any sub-dialog closes (groupValues may have
        // gained required entries). See makeGroupButton overload below.
        groupValidationTrigger = revalidate;
        // Initial pass so an already-satisfied form starts with OK enabled,
        // and an unsatisfied one starts disabled.
        revalidate.run();

        pane.setContent(new javafx.scene.layout.VBox(scopeBox, body));

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            Map<String, String> values = new LinkedHashMap<>();
            for (ParamSpec spec : specs) {
                String k = keyFor(spec);
                Node n = inputs.get(k);
                if (n != null) {
                    values.put(k, readInput(n));
                } else if (spec.group != null) {
                    // Came from a dialog-group: pull from the sub-dialog result map.
                    Map<String, String> sub = groupValues.get(spec.group);
                    String v = sub == null ? null : sub.get(k);
                    // If the sub-dialog was never opened, fall back to the spec default.
                    if (v == null) v = spec.defaultValue;
                    values.put(k, v == null ? "" : v);
                } else {
                    values.put(k, "");
                }
            }
            // Enforce inline-group visibility: hidden inline params contribute nothing.
            for (Map.Entry<String, List<ParamSpec>> ge : byGroup.entrySet()) {
                SchemaLoader.GroupSpec gs = groupDefs.get(ge.getKey());
                if (gs.render != SchemaLoader.GroupSpec.Render.INLINE) continue;
                if (gs.visibleWhen == null) continue;
                String src = valueFor(gs.visibleWhen.flag, inputs, values);
                if (!gs.visibleWhen.test(src)) {
                    for (ParamSpec ps : ge.getValue()) values.put(keyFor(ps), "");
                }
            }
            return values;
        });

        Map<String, String> result = dialog.showAndWait().orElse(null);
        if (result == null) return;

        // Persist the captured values so re-opening this subcommand's dialog
        // seeds the widgets with the user's previous input. `result` already
        // contains main-grid, inline-group, and dialog-group values (merged
        // by the result converter), making it the canonical snapshot.
        LastUsedValues.save(subcommand, result);

        // --- Resolve the user's scope choice -----------------------------
        RunScope scope;
        {
            Toggle chosen = scopeGroup.getSelectedToggle();
            if (chosen == rbSelection) {
                if (pickedEntries.isEmpty()) {
                    Dialogs.showErrorMessage("wsinsight",
                            "No project images were selected. Click the radio button again to pick.");
                    return;
                }
                scope = RunScope.fromProjectSelection(pickedEntries);
            } else if (chosen == rbAll) {
                scope = RunScope.fromProjectAll(project);
            } else {
                scope = currentScope;
            }
            if (scope == null) {
                Dialogs.showErrorMessage("wsinsight",
                        "Could not resolve the chosen slide scope to any local files.");
                return;
            }
        }

        java.io.File slidesMountRoot = scope.slidesMountRoot();
        if (slidesMountRoot == null || !slidesMountRoot.isDirectory()) {
            Dialogs.showErrorMessage("wsinsight",
                    "Slides mount root is not a directory: " + slidesMountRoot);
            return;
        }

        // Materialise the image-list manifest up-front if we're batching.
        String wsiDirOverride;
        try {
            wsiDirOverride = scope.writeImageListIfNeeded(resultsDir);
        } catch (java.io.IOException e) {
            Dialogs.showErrorMessage("wsinsight",
                    "Failed to write slide list: " + e.getMessage());
            return;
        }

        // --- Build argv ---------------------------------------------------
        WSInsightSetup setup = WSInsightSetup.getInstance();
        DockerRunner.Builder rb = DockerRunner.builder().fromSetup(setup);
        // fromSetup handles extra mounts; mount the scope-derived slidesMountRoot
        // and the scratch results dir.
        rb.mount(new qupath.ext.wsinsight.runner.PathMapper.Mount(
                slidesMountRoot.toPath(), "/slides"));
        rb.mount(new qupath.ext.wsinsight.runner.PathMapper.Mount(
                resultsDir.toPath(), "/results"));
        rb.arg(subcommand);
        qupath.ext.wsinsight.runner.PathMapper pm = buildPathMapper(
                setup, slidesMountRoot, resultsDir);

        List<ParamSpec> missing = new ArrayList<>();
        for (ParamSpec spec : specs) {
            String val = result.get(keyFor(spec));
            if (spec.required && (val == null || val.isBlank())) {
                missing.add(spec);
                continue;
            }
            if (val == null || val.isBlank()) continue;

            switch (spec.kind) {
                case BOOL_FLAG:
                    if ("true".equalsIgnoreCase(val)) rb.arg(spec.flag);
                    break;
                case PATH:
                    String resolved = val;
                    if (spec.translatePath) {
                        String mapped = pm.hostToContainer(val);
                        if (mapped == null) {
                            throw new IllegalStateException(
                                    "Path '" + val + "' is not covered by any configured Docker bind mount. "
                                    + "Add it under 'Extra mounts' in Edit → Preferences → WSInsight.");
                        }
                        resolved = mapped;
                    }
                    if (spec.flag != null) rb.arg(spec.flag);
                    rb.arg(resolved);
                    break;
                default:
                    // Registry-backed --model dropdown: the user picked a
                    // display label, so emit -z <container-path-to-model-dir>
                    // instead of -m <name>. Requires the registry's parent
                    // directory to be mounted via 'Extra mounts'.
                    if ("--model".equals(spec.flag) && zooByLabel.containsKey(val)) {
                        ZooRegistry.Entry entry = zooByLabel.get(val);
                        String mapped = pm.hostToContainer(entry.hostDir.toString());
                        if (mapped == null) {
                            throw new IllegalStateException(
                                    "Zoo model directory '" + entry.hostDir
                                    + "' is not covered by any configured Docker bind mount. "
                                    + "Add the zoo registry parent directory under 'Extra mounts' in "
                                    + "Edit → Preferences → WSInsight.");
                        }
                        rb.arg("-z").arg(mapped);
                        break;
                    }
                    if (spec.flag != null) rb.arg(spec.flag);
                    rb.arg(val);
                    break;
            }
        }

        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("Missing required: ");
            for (ParamSpec s : missing) sb.append(s.label).append(", ");
            throw new IllegalArgumentException(sb.toString());
        }

        // Auto-append --wsi-dir / --results-dir using the in-container bind
        // mount targets. When the scope produced a multi-slide image-list
        // manifest, point --wsi-dir at the image-list:// URI instead of the
        // plain /slides mount so wsinsight processes exactly the chosen set.
        for (Map.Entry<String, String> e : AUTO_PATH_FLAGS.entrySet()) {
            if (!autoFlagsForCommand.contains(e.getKey())) continue;
            String value = e.getValue();
            if ("--wsi-dir".equals(e.getKey()) && wsiDirOverride != null) {
                value = wsiDirOverride;
            }
            rb.arg(e.getKey()).arg(value);
        }

        DockerRunner runner = rb.build();
        // Pre-flight: ensure the configured image exists locally. If not,
        // offer to pull it before launching the real workload so a missing
        // image surfaces as a clear prompt instead of a cryptic `docker run`
        // failure deep in the progress log.
        if (!DockerRunner.imageExists(setup.getDockerBinary(), setup.getDockerImage())) {
            boolean pull = Dialogs.showYesNoDialog(
                    "WSInsight",
                    "Docker image not found locally:\n    "
                            + setup.getDockerImage()
                            + "\n\nPull it now? (This may take several minutes.)");
            if (!pull) return;
            int rc = DockerPull.runWithProgress(setup.getDockerBinary(), setup.getDockerImage());
            if (rc != 0) {
                Dialogs.showErrorMessage("wsinsight",
                        "docker pull failed (exit code " + rc + "). See log for details.");
                return;
            }
        }

        WSInsightProgressDialog progress = new WSInsightProgressDialog("wsinsight — " + subcommand, runner);
        // Import GeoJSON annotations produced under the effective results dir
        // back into the matching project image(s) when the run succeeds.
        final java.io.File importDir = resultsDir;
        final RunScope importScope = scope;
        final Project<?> importProject = project;
        if (setup.isAutoImportResults()) {
            progress.setOnFinished(() -> {
                Integer code = progress.getExitCode();
                if (code != null && code == 0) {
                    AutoImport.importResults(importDir, importScope, importProject);
                }
            });
        }
        progress.showAndRun();
    }

    /**
     * Build a button that opens the group's sub-dialog. The button label comes
     * from the group spec; clicks open {@link #openGroupDialog} with persisted
     * prior values from {@code groupValues}.
     */
    private Button makeGroupButton(SchemaLoader.GroupSpec group,
                                   List<ParamSpec> params,
                                   Map<String, Map<String, String>> groupValues) {
        String btnLabel = group.buttonLabel != null && !group.buttonLabel.isBlank()
                ? group.buttonLabel : group.title + "…";
        Button btn = new Button(btnLabel);
        btn.setOnAction(ev -> {
            Map<String, String> prev = groupValues.getOrDefault(group.key, Map.of());
            Map<String, String> res = openGroupDialog(group, params, prev);
            if (res != null) groupValues.put(group.key, res);
            // Required fields may now be satisfied (or invalidated) by the
            // user's edits inside the sub-dialog; re-run the OK-gate check.
            if (groupValidationTrigger != null) groupValidationTrigger.run();
        });
        return btn;
    }

    /**
     * Re-evaluate OK-button enable state; set by the main form builder so that
     * sub-dialog closures can trigger a re-check (dialog-group values are
     * otherwise only read inside the main result converter).
     */
    private Runnable groupValidationTrigger;

    /**
     * Attach a change listener to {@code n} that calls {@code r} on every
     * user edit. Used by required-field validation to keep the OK button's
     * enable state in sync with the widgets.
     */
    private static void attachChangeListener(Node n, Runnable r) {
        if (n instanceof CheckBox cb) {
            cb.selectedProperty().addListener((o, a, b) -> r.run());
        } else if (n instanceof ChoiceBox<?> cb) {
            cb.valueProperty().addListener((o, a, b) -> r.run());
        } else if (n instanceof TextField tf) {
            tf.textProperty().addListener((o, a, b) -> r.run());
        } else if (n instanceof HBox box) {
            for (Node child : box.getChildren()) {
                if (child instanceof TextField tf) {
                    tf.textProperty().addListener((o, a, b) -> r.run());
                    break;
                }
            }
        }
    }

    /**
     * Open a modal sub-dialog collecting values for one {@link SchemaLoader.GroupSpec}.
     * Returns flag → textual value on OK, or {@code null} on cancel. Prior values
     * ({@code prev}) are seeded into the controls so re-opening the dialog preserves
     * previous user input.
     */
    private Map<String, String> openGroupDialog(
            SchemaLoader.GroupSpec group, List<ParamSpec> params,
            Map<String, String> prev) {
        Dialog<Map<String, String>> dlg = new Dialog<>();
        dlg.setTitle("wsinsight — " + group.title);
        dlg.setHeaderText(group.title);
        DialogPane dp = dlg.getDialogPane();
        dp.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(8));

        Map<String, Node> subInputs = new LinkedHashMap<>();
        for (int i = 0; i < params.size(); i++) {
            ParamSpec spec = params.get(i);
            Label label = new Label(spec.label + (spec.required ? " *" : "") + ":");
            if (!spec.help.isBlank()) label.setTooltip(new Tooltip(spec.help));
            Node input = buildInput(spec);
            // Seed with previously entered value if any.
            String seed = prev.get(keyFor(spec));
            if (seed != null && !seed.isEmpty()) writeInput(input, seed);
            g.add(label, 0, i);
            g.add(input, 1, i);
            subInputs.put(keyFor(spec), input);
        }
        dp.setContent(g);

        dlg.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            Map<String, String> out = new LinkedHashMap<>();
            for (ParamSpec spec : params) {
                out.put(keyFor(spec), readInput(subInputs.get(keyFor(spec))));
            }
            return out;
        });
        return dlg.showAndWait().orElse(null);
    }

    /** Short summary for the dialog-group button (e.g. {@code "3 set"} or the lone value). */
    private static String summariseGroup(List<ParamSpec> params, Map<String, String> values) {
        if (params.size() == 1) {
            ParamSpec only = params.get(0);
            String v = values.get(keyFor(only));
            if (v == null || v.isBlank() || "false".equalsIgnoreCase(v)) return "";
            return v.length() > 48 ? "…" + v.substring(v.length() - 45) : v;
        }
        long n = 0;
        for (ParamSpec ps : params) {
            String v = values.get(keyFor(ps));
            if (v == null || v.isBlank()) continue;
            if ("false".equalsIgnoreCase(v)) continue;
            if (v.equals(ps.defaultValue)) continue;
            n++;
        }
        return n == 0 ? "" : n + " set";
    }

    /**
     * Attach a visibility binding: whenever the control for {@code vw.flag}
     * changes, recompute and apply {@code setVisible/setManaged} on {@code node}.
     */
    private void bindVisibility(Node node, ParamSpec.VisibleWhen vw, Map<String, Node> inputs) {
        if (vw == null) return;
        Node src = inputs.get(vw.flag);
        Runnable apply = () -> {
            String v = src == null ? "" : readInput(src);
            boolean vis = vw.test(v);
            node.setVisible(vis);
            node.setManaged(vis);
            // Resize the enclosing window so newly-shown rows fit (and OK/Cancel
            // stay on-screen) without leaving stale empty space when hidden.
            javafx.scene.Scene scene = node.getScene();
            if (scene != null && scene.getWindow() != null)
                scene.getWindow().sizeToScene();
        };
        apply.run();
        if (src instanceof CheckBox cb) {
            cb.selectedProperty().addListener((o, a, b) -> apply.run());
        } else if (src instanceof ChoiceBox<?> cb) {
            cb.valueProperty().addListener((o, a, b) -> apply.run());
        } else if (src instanceof TextField tf) {
            tf.textProperty().addListener((o, a, b) -> apply.run());
        } else if (src instanceof HBox box) {
            // PATH rows wrap the TextField inside an HBox.
            for (Node child : box.getChildren()) {
                if (child instanceof TextField tf) {
                    tf.textProperty().addListener((o, a, b) -> apply.run());
                    break;
                }
            }
        }
    }

    /** Look up the current value of {@code flag} from either the live inputs map or the captured values map. */
    private String valueFor(String flag, Map<String, Node> inputs, Map<String, String> values) {
        Node n = inputs.get(flag);
        if (n != null) return readInput(n);
        String v = values.get(flag);
        return v == null ? "" : v;
    }

    /** Seed an input control with a previously captured textual value. */
    private void writeInput(Node n, String value) {
        if (n instanceof CheckBox cb) cb.setSelected("true".equalsIgnoreCase(value));
        else if (n instanceof ChoiceBox<?> cb) {
            @SuppressWarnings("unchecked")
            ChoiceBox<String> cbs = (ChoiceBox<String>) cb;
            if (cbs.getItems().contains(value)) cbs.setValue(value);
        }
        else if (n instanceof TextField tf) tf.setText(value);
        else if (n instanceof HBox box) {
            for (Node child : box.getChildren()) {
                if (child instanceof TextField tf) { tf.setText(value); break; }
            }
        }
    }

    /**
     * Open a modal multi-select dialog listing every image in the project.
     * Returns the entries the user checked (possibly empty), or {@code null}
     * if the user cancelled.
     */
    private List<ProjectImageEntry<?>> pickProjectEntries(
            Project<?> project, List<ProjectImageEntry<?>> preSelected) {
        Dialog<List<ProjectImageEntry<?>>> dlg = new Dialog<>();
        dlg.setTitle("WSInsight — Select project images");
        dlg.setHeaderText("Choose the images to process:");
        DialogPane dp = dlg.getDialogPane();
        dp.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ObservableList<ProjectImageEntry<?>> items =
                FXCollections.observableArrayList(project.getImageList());
        java.util.Set<ProjectImageEntry<?>> checked = new java.util.HashSet<>(preSelected);
        ListView<ProjectImageEntry<?>> lv = new ListView<>(items);
        lv.setPrefSize(480, 360);
        lv.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        Callback<ProjectImageEntry<?>, javafx.beans.value.ObservableValue<Boolean>> cbCallback =
                entry -> {
                    javafx.beans.property.SimpleBooleanProperty prop =
                            new javafx.beans.property.SimpleBooleanProperty(checked.contains(entry));
                    prop.addListener((obs, was, now) -> {
                        if (now) checked.add(entry); else checked.remove(entry);
                    });
                    return prop;
                };
        lv.setCellFactory(CheckBoxListCell.forListView(cbCallback,
                new javafx.util.StringConverter<>() {
                    @Override public String toString(ProjectImageEntry<?> e) {
                        return e == null ? "" : e.getImageName();
                    }
                    @Override public ProjectImageEntry<?> fromString(String s) { return null; }
                }));

        Button selectAll = new Button("All");
        selectAll.setOnAction(ev -> {
            checked.addAll(items);
            lv.refresh();
        });
        Button selectNone = new Button("None");
        selectNone.setOnAction(ev -> {
            checked.clear();
            lv.refresh();
        });
        HBox actions = new HBox(6, selectAll, selectNone);
        VBox content = new VBox(6, lv, actions);
        dp.setContent(content);

        dlg.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            List<ProjectImageEntry<?>> out = new ArrayList<>();
            for (ProjectImageEntry<?> e : items) {
                if (checked.contains(e)) out.add(e);
            }
            return out;
        });
        return dlg.showAndWait().orElse(null);
    }

    private static String keyFor(ParamSpec s) {
        return s.flag != null ? s.flag : s.label;
    }

    private Node buildInput(ParamSpec spec) {
        switch (spec.kind) {
            case BOOL_FLAG: {
                CheckBox cb = new CheckBox();
                cb.setSelected("true".equalsIgnoreCase(spec.defaultValue));
                return cb;
            }
            case CHOICE: {
                ChoiceBox<String> box = new ChoiceBox<>();
                // When the user configured WSINSIGHT_ZOO_REGISTRY_PATH, the
                // --model dropdown shows labels resolved from each model's
                // config.json (or registry description/key fallback) — see
                // ZooRegistry. Submit translates the label to -z <dir>.
                if ("--model".equals(spec.flag) && !zooByLabel.isEmpty()) {
                    box.getItems().addAll(zooByLabel.keySet());
                    box.setValue(box.getItems().get(0));
                } else {
                    box.getItems().addAll(spec.choices);
                    if (!spec.defaultValue.isEmpty() && spec.choices.contains(spec.defaultValue))
                        box.setValue(spec.defaultValue);
                    else if (!spec.choices.isEmpty())
                        box.setValue(spec.choices.get(0));
                }
                return box;
            }
            case PATH: {
                TextField tf = new TextField(spec.defaultValue);
                tf.setPrefColumnCount(28);
                Button browse = new Button("…");
                browse.setTooltip(new Tooltip("Browse…"));
                browse.setOnAction(ev -> {
                    // Name-based dir-vs-file heuristic. Anything whose label
                    // or flag suggests "file"/"config"/"path" opens a file
                    // chooser; otherwise (default, matches --wsi-dir,
                    // --results-dir, --*-dir, --*-cache-dir, etc.) we open
                    // a directory chooser.
                    String hint = (spec.label + " " + (spec.flag == null ? "" : spec.flag))
                            .toLowerCase();
                    boolean isFile = hint.contains("file")
                            || hint.contains("config")
                            || hint.contains("model-path")
                            || hint.contains("registry")
                            || hint.contains("manifest");
                    if (isFile) {
                        FileChooser fc = new FileChooser();
                        if (!tf.getText().isBlank()) {
                            File init = new File(tf.getText());
                            if (init.getParentFile() != null && init.getParentFile().isDirectory())
                                fc.setInitialDirectory(init.getParentFile());
                        }
                        File f = fc.showOpenDialog(null);
                        if (f != null) tf.setText(f.getAbsolutePath());
                    } else {
                        DirectoryChooser dc = new DirectoryChooser();
                        if (!tf.getText().isBlank()) {
                            File init = new File(tf.getText());
                            if (init.isDirectory()) dc.setInitialDirectory(init);
                            else if (init.getParentFile() != null && init.getParentFile().isDirectory())
                                dc.setInitialDirectory(init.getParentFile());
                        }
                        File f = dc.showDialog(null);
                        if (f != null) tf.setText(f.getAbsolutePath());
                    }
                });
                HBox box = new HBox(4, tf, browse);
                return box;
            }
            default: {
                TextField tf = new TextField(spec.defaultValue);
                tf.setPrefColumnCount(28);
                return tf;
            }
        }
    }

    private String readInput(Node n) {
        if (n instanceof CheckBox cb) return Boolean.toString(cb.isSelected());
        if (n instanceof ChoiceBox<?> cb) {
            Object v = cb.getValue();
            return v == null ? "" : v.toString();
        }
        if (n instanceof TextField tf) return tf.getText();
        if (n instanceof HBox box && !box.getChildren().isEmpty() && box.getChildren().get(0) instanceof TextField tf)
            return tf.getText();
        return "";
    }

    private static qupath.ext.wsinsight.runner.PathMapper buildPathMapper(
            WSInsightSetup setup, File effectiveWsiDir, File effectiveResultsDir) {
        List<qupath.ext.wsinsight.runner.PathMapper.Mount> mounts = new ArrayList<>();
        if (effectiveWsiDir != null)
            mounts.add(new qupath.ext.wsinsight.runner.PathMapper.Mount(effectiveWsiDir.toPath(), "/slides"));
        if (effectiveResultsDir != null)
            mounts.add(new qupath.ext.wsinsight.runner.PathMapper.Mount(effectiveResultsDir.toPath(), "/results"));
        for (String entry : setup.getExtraMounts().split("[,;\\n]")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int idx = e.lastIndexOf(':');
            if (idx <= 0) continue;
            mounts.add(new qupath.ext.wsinsight.runner.PathMapper.Mount(
                    new File(e.substring(0, idx)).toPath(), e.substring(idx + 1)));
        }
        return new qupath.ext.wsinsight.runner.PathMapper(mounts);
    }

    /**
     * Resolve the host directory that will be bind-mounted at {@code /results}.
     * When a QuPath project is open and its base directory is on the local
     * file system, results land under
     * {@code <project>/wsinsight-runs/<subcommand>-<timestamp>/} so they
     * travel with the project and can be re-inspected later. Otherwise, a
     * fresh scratch directory under the system temp folder is created.
     */
    private File resolveHostResultsRoot(WSInsightSetup setup, Project<?> project) {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String leaf = sanitize(subcommand) + "-" + stamp;
        if (project != null) {
            File base = Projects.getBaseDirectory(project);
            if (base != null && base.isDirectory()) {
                File dir = new File(new File(base, "wsinsight-runs"), leaf);
                if (dir.mkdirs() || dir.isDirectory()) return dir;
            }
        }
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory(
                    "wsinsight-" + leaf + "-");
            return tmp.toFile();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String sanitize(String s) {
        return s == null ? "cmd" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
