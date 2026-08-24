package qupath.ext.wsinsight.commands;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
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

import org.slf4j.LoggerFactory;

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

    /** Auto-supplied flags the user may still override from the form. */
    private static final java.util.Set<String> OPTIONAL_AUTO_FLAGS =
            java.util.Set.of("--results-dir");

    /** Same parameter, but blank is allowed because a value is derived when empty. */
    private static ParamSpec asOptional(ParamSpec s) {
        return ParamSpec.builder()
                .flag(s.flag)
                .label(s.label)
                .help("Leave blank to create a new timestamped folder. "
                        + "Point it at an earlier run to reuse those results.")
                .kind(s.kind)
                .defaultValue("")
                .choices(s.choices)
                .translatePath(s.translatePath)
                .required(false)
                .group(s.group)
                .columnBreak(s.columnBreak)
                .nargs(s.nargs)
                .build();
    }

    private final String title;
    private final String subcommand;
    private final List<ParamSpec> specs;
    /**
     * Chain-input flags lifted out of the main grid into a "Chain from
     * previous run" subsection that the user reveals by ticking a checkbox;
     * still iterated as part of {@link #specs} elsewhere so argv building and
     * result conversion see them uniformly.
     */
    private final List<ParamSpec> chainSpecs;
    /**
     * External-input flags lifted out of the main grid into a collapsed
     * "External inputs" subsection; the user expands the panel to fill them
     * in. Same flow as {@link #chainSpecs}: visible on the form, included
     * in {@link #specs} for argv building.
     */
    private final List<ParamSpec> externalSpecs;
    private final Map<String, SchemaLoader.GroupSpec> groupDefs;
    private final java.util.Set<String> autoFlagsForCommand;
    /**
     * Zoo models reported by the CLI schema, keyed by dropdown label.
     * Empty when the schema predates the models section, in which case the
     * picker falls back to the schema's hard-coded {@code --model} choices.
     */
    private final Map<String, SchemaLoader.ModelSpec> modelsByLabel = new LinkedHashMap<>();
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
                // The results directory stays on the form so a previous run's
                // outputs can be reused; blank means "pick one for me".
                if (OPTIONAL_AUTO_FLAGS.contains(s.flag)) {
                    visible.add(asOptional(s));
                }
                continue;
            }
            if (s.flag != null && HIDDEN_MODEL_FLAGS.contains(s.flag)) continue;
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
        // The model chooser now supplies --zoo-model-dir, so lead with it.
        for (int i = 0; i < visible.size(); i++) {
            if ("--model".equals(visible.get(i).flag)) {
                visible.add(0, visible.remove(i));
                break;
            }
        }
        // Pull chain-input flags (region- / object-inference-dir) out of the
        // main grid into a "Chain from previous run" subsection gated by a
        // checkbox. The specs still flow through the same arg-building path,
        // so the form values reach argv without special-casing.
        List<ParamSpec> chain = new ArrayList<>();
        // External-input flags (e.g. --histoqc-dir) are also pushed off the
        // main grid so the dialog stays focused on the canonical wsinsight
        // workflow; they live in a collapsed "External inputs" panel below.
        List<ParamSpec> external = new ArrayList<>();
        for (Iterator<ParamSpec> it = visible.iterator(); it.hasNext(); ) {
            ParamSpec s = it.next();
            if (s.flag == null) continue;
            if (CHAIN_INPUT_FLAGS.contains(s.flag)) {
                chain.add(asOptional(s));
                it.remove();
            } else if (EXTERNAL_INPUT_FLAGS.contains(s.flag)) {
                external.add(asOptional(s));
                it.remove();
            }
        }
        // `applyMainOrder` knows about --region-inference-dir as the anchor
        // for --histoqc-dir, but those anchors now live in `chain` and
        // `external`. Apply once on the merged list (so the static test
        // exercising the rules still sees both kinds in place), then again
        // on `visible` (whose only surviving rules govern --stitch-workers
        // placement). Rules that name an absent flag are skipped by design.
        List<ParamSpec> ordered = new ArrayList<>(visible);
        ordered.addAll(chain);
        ordered.addAll(external);
        applyMainOrder(ordered);
        applyMainOrder(visible);
        this.specs = ordered;
        this.chainSpecs = chain;
        this.externalSpecs = external;
        this.autoFlagsForCommand = auto;
    }

    /**
     * Ways of locating a model that the dropdown already determines, or that
     * point at model weights the user is expected to have provisioned once
     * (H-Optimus lives on the host under the HF cache). Showing these as
     * separate fields invites values that contradict the chosen model, and
     * the CLI rejects them as mutually exclusive anyway.
     */
    private static final java.util.Set<String> HIDDEN_MODEL_FLAGS =
            java.util.Set.of("--zoo-model-dir", "--config", "--model-path",
                    "--hoptimus-model-dir", "--niche-hoptimus-model-dir");

    /** Experimental {@code run}-dialog flags hidden when the pref is off. */
    private static final java.util.Set<String> EXPERIMENTAL_FLAGS =
            java.util.Set.of("--hplot", "--niche", "--ecomp", "--tcomp",
                    "--agg", "--import");
    /** Experimental group keys hidden when the pref is off. */
    private static final java.util.Set<String> EXPERIMENTAL_GROUPS =
            java.util.Set.of("hplot_tuning", "niche_tuning", "ecomp_tuning", "tcomp_tuning");

    /**
     * Chain-input flags: previous WSInsight runs' results directories that
     * {@code run} / {@code patch} / {@code infer} / {@code reg} consume as a
     * downstream input. The user enables them by ticking the "Chain from
     * previous run" checkbox in the form; the corresponding dir fields are
     * hidden by default so the dialog stays focused on the canonical case.
     */
    private static final java.util.Set<String> CHAIN_INPUT_FLAGS =
            java.util.Set.of("--region-inference-dir", "--object-inference-dir");

    /**
     * External-input flags: directories produced by tools other than wsinsight
     * (e.g. HistoQC's slide-level QC outputs) that the CLI consumes as a
     * preprocessing input. These are uncommon; park them in a collapsed
     * "External inputs" section so the main grid stays focused on the
     * canonical wsinsight workflow.
     */
    private static final java.util.Set<String> EXTERNAL_INPUT_FLAGS =
            java.util.Set.of("--histoqc-dir");

    /** Minimum members before a flag prefix earns its own collapsible section. */
    private static final int AUTO_SECTION_MIN = 2;

    /**
     * Group params by the first segment of their flag ({@code --niche-k} → "niche").
     * A bare master switch ({@code --niche}) has no segment and stays in the main
     * grid, as do required params — a required field inside a collapsed section
     * would disable OK with no visible cause.
     */
    static LinkedHashMap<String, List<ParamSpec>> deriveSections(List<ParamSpec> specs) {
        LinkedHashMap<String, List<ParamSpec>> byPrefix = new LinkedHashMap<>();
        for (ParamSpec s : specs) {
            if (s.required || s.flag == null || !s.flag.startsWith("--")) continue;
            String rest = s.flag.substring(2);
            int dash = rest.indexOf('-');
            if (dash <= 0) continue;
            byPrefix.computeIfAbsent(rest.substring(0, dash), k -> new ArrayList<>()).add(s);
        }
        byPrefix.values().removeIf(v -> v.size() < AUTO_SECTION_MIN);
        return byPrefix;
    }

    /**
     * Main-grid placement, as flag &rarr; the flag it should follow. The CLI
     * declaration order groups these poorly: the two worker counts end up in
     * opposite columns, and the two directory pickers are split apart.
     * Rules are applied in order, so one may build on another.
     *
     * <p>The two special anchor tokens {@code __TOP__} and {@code __BOTTOM__}
     * (handled in {@link #applyMainOrder}) replace the conventional "follow
     * flag" anchor so a rule can pin a param to the first or last row of the
     * main grid. As of 2026-08-23 this is used to push {@code --results-dir}
     * to the very top of every dialog so users see and update the output path
     * before everything else.
     */
    static final String ANCHOR_TOP = "__TOP__";
    static final String ANCHOR_BOTTOM = "__BOTTOM__";

    private static final List<Map.Entry<String, String>> MAIN_ORDER_AFTER = List.of(
            Map.entry("--stitch-workers", "--num-workers"),
            Map.entry("--region-inference-dir", "--overwrite"),
            Map.entry("--histoqc-dir", "--region-inference-dir"),
            Map.entry("--results-dir", ANCHOR_TOP)            // 2026-08-23: results-dir to top
    );

    /** Reorder the main grid in place; rules naming an absent flag are skipped. */
    static void applyMainOrder(List<ParamSpec> specs) {
        for (Map.Entry<String, String> rule : MAIN_ORDER_AFTER) {
            int from = indexOfFlag(specs, rule.getKey());
            if (from < 0)
                continue;
            String anchor = rule.getValue();
            if (ANCHOR_TOP.equals(anchor)) {
                // Move to index 0 (very top of the dialog).
                ParamSpec moved = specs.remove(from);
                specs.add(0, moved);
            } else if (ANCHOR_BOTTOM.equals(anchor)) {
                // Move to the end (very bottom).
                ParamSpec moved = specs.remove(from);
                specs.add(moved);
            } else {
                int after = indexOfFlag(specs, anchor);
                if (after < 0) {
                    // Anchor flag absent for this subcommand — skip the rule
                    // entirely, leaving the moved-from element in its
                    // original-ish position. Pre-fix: we used specs.add(0, …)
                    // here, which broke `rulesNamingAnAbsentFlagAreSkipped`.
                    continue;
                }
                // Remove the source element first; the anchor index then
                // shifts left by one if it sat *after* the source. Re-fetch
                // the anchor index against the now-shorter list to avoid an
                // off-by-one (IndexOutOfBoundsException) the previous code
                // tripped over when `from < after`.
                ParamSpec moved = specs.remove(from);
                int afterAfter = indexOfFlag(specs, anchor);
                if (afterAfter < 0) {
                    // Anchor disappeared (mustn't happen, the anchor was
                    // re-checked above) — fall back to appending.
                    specs.add(moved);
                } else {
                    specs.add(afterAfter + 1, moved);
                }
            }
        }
    }

    private static int indexOfFlag(List<ParamSpec> specs, String flag) {
        for (int i = 0; i < specs.size(); i++) {
            if (flag.equals(specs.get(i).flag))
                return i;
        }
        return -1;
    }

    /**
     * Remove each section's master switch ({@code --ncomp} for the
     * {@code ncomp} section) from the main grid, so it can be shown on the
     * section itself rather than adrift among unrelated fields.
     */
    static LinkedHashMap<String, ParamSpec> takeSectionSwitches(
            List<ParamSpec> mainSpecs, java.util.Set<String> prefixes) {
        LinkedHashMap<String, ParamSpec> switches = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            String flag = "--" + prefix;
            for (java.util.Iterator<ParamSpec> it = mainSpecs.iterator(); it.hasNext(); ) {
                ParamSpec s = it.next();
                if (flag.equals(s.flag) && s.kind == ParamSpec.Kind.BOOL_FLAG) {
                    switches.put(prefix, s);
                    it.remove();
                    break;
                }
            }
        }
        return switches;
    }

    /**
     * Opening height of the scrollable body. We open at the body's natural
     * preferred height (so a near-empty dialog is tiny, a packed one shows
     * everything) and clamp it between a floor (so sparse forms still read
     * as a dialog) and {@code 85%} of the screen (so the OK button is
     * always reachable on laptops).
     */
    static double preferredBodyHeight(double naturalHeight, double screenHeight) {
        // +24 is a small fudge so the bottom row of labels isn't clipped by
        // borders / insets after JavaFX lays everything out.
        double sized = naturalHeight + 24;
        return Math.max(360, Math.min(sized, screenHeight * 0.85));
    }

    /**
     * Options the CLI declares on its top-level group rather than on any
     * subcommand, so they are not in the schema and cannot come from the form.
     */
    static List<String> globalArgs(String wsiBackend) {
        if (wsiBackend == null || wsiBackend.isBlank()
                || WSInsightSetup.WSI_BACKEND_AUTO.equals(wsiBackend))
            return List.of();
        return List.of("--backend", wsiBackend);
    }

    /** Section titles match the flags they contain, so they stay lower case. */
    static String sectionTitle(String prefix) {
        return prefix;
    }

    /** Show the parameter form; on OK, launch the container and block until it finishes. */
    public void showAndRun() {
        WSInsightSetup setupPre = WSInsightSetup.getInstance();
        qupath.lib.gui.QuPathGUI gui = qupath.lib.gui.QuPathGUI.getInstance();

        // Models come from the CLI schema, so the list reflects the environment
        // that will actually run inference rather than the host's directory tree.
        modelsByLabel.clear();
        try {
            for (SchemaLoader.ModelSpec m : WSInsightCommands.schema().models())
                modelsByLabel.putIfAbsent(m.label(), m);
        } catch (java.io.IOException e) {
            LoggerFactory.getLogger(GenericCommandDialog.class)
                    .warn("Could not read models from CLI schema: {}", e.getMessage());
        }

        // Previously-saved user input for this subcommand (per-flag values).
        // Used further down to seed widgets after they're built and to
        // pre-populate per-group sub-dialog state so re-opening a sub-dialog
        // restores the user's last choices.
        //
        // 2026-08-23: Preference layers, project-scoped memory wins when
        // a project is open, otherwise the user-wide LastUsedValues fallback.
        // The user-wide layer remains so single-image workflows (no project
        // loaded) still recall the previous value.
        Project<?> project = gui != null ? gui.getProject() : null;

        // Previously-saved user input for this subcommand (per-flag values).
        // Used further down to seed widgets after they're built and to
        // pre-populate per-group sub-dialog state so re-opening a sub-dialog
        // restores the user's last choices.
        //
        // 2026-08-23: Preference layers, project-scoped memory wins when
        // a project is open, otherwise the user-wide LastUsedValues fallback.
        // The user-wide layer remains so single-image workflows (no project
        // loaded) still recall the previous value.
        Map<String, String> lastUsed;
        if (ProjectLastUsedValues.hasProject(project)) {
            lastUsed = ProjectLastUsedValues.load(project, subcommand);
        } else {
            lastUsed = LastUsedValues.load(subcommand);
        }
        // 2026-08-23 (shared results-dir): overlay the project's most
        // recent --results-dir on top, regardless of which subcommand
        // wrote it last. Other widgets keep their per-subcommand values
        // so e.g. model_path stays per-subcommand.
        SharedResultsDir.read(project).ifPresent(p ->
                lastUsed.put(SharedResultsDir.KEY_PATH, p));

        // --- Scope availability ------------------------------------------
        RunScope currentScope = RunScope.fromCurrentImage(gui);
        boolean haveProject = project != null && !project.getImageList().isEmpty();

        if (currentScope == null && !haveProject) {
            Dialogs.showErrorMessage("wsinsight",
                    "No image available. Open a slide or open a project with images.");
            return;
        }

        // --- Build dialog -------------------------------------------------
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("wsinsight — " + subcommand);

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Scope selector row lives in its own VBox above the two-column body.
        // No "all images" option: the picker's own All button covers it.
        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton rbCurrent = new RadioButton("Current image");
        RadioButton rbSelection = new RadioButton("Selected project images…");
        rbCurrent.setToggleGroup(scopeGroup);
        rbSelection.setToggleGroup(scopeGroup);
        rbCurrent.setDisable(currentScope == null);
        rbSelection.setDisable(!haveProject);
        final List<ProjectImageEntry<?>> pickedEntries = new ArrayList<>();
        rbSelection.setOnAction(ev -> {
            if (project == null) return;
            List<ProjectImageEntry<?>> picked = pickProjectEntries(project, pickedEntries);
            if (picked == null) {
                if (!rbCurrent.isDisabled()) rbCurrent.setSelected(true);
                return;
            }
            pickedEntries.clear();
            pickedEntries.addAll(picked);
            rbSelection.setText("Selected project images… (" + picked.size() + ")");
        });
        if (!rbCurrent.isDisabled()) rbCurrent.setSelected(true);
        else if (!rbSelection.isDisabled()) rbSelection.setSelected(true);

        VBox scopeBox = new VBox(4,
                new Label("Process:"),
                new HBox(12, rbCurrent, rbSelection));
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

        // `describe` carries no GUI hints, so fall back to the CLI's own flag
        // prefixes (--niche-*, --hplot-*, ...) to keep the main form short.
        LinkedHashMap<String, List<ParamSpec>> autoSections = new LinkedHashMap<>();
        Map<String, ParamSpec> sectionSwitches = new LinkedHashMap<>();
        if (groupDefs.isEmpty()) {
            autoSections.putAll(deriveSections(mainSpecs));
            for (List<ParamSpec> v : autoSections.values()) mainSpecs.removeAll(v);
            sectionSwitches.putAll(takeSectionSwitches(mainSpecs, autoSections.keySet()));
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
            if ("--model".equals(spec.flag) && input instanceof ChoiceBox<?> cbx
                    && !modelsByLabel.isEmpty()) {
                // Short text, full path in the tooltip: a path here would widen
                // the whole dialog.
                Label origin = new Label();
                Tooltip originTip = new Tooltip();
                Runnable refresh = () -> {
                    SchemaLoader.ModelSpec m = modelsByLabel.get(String.valueOf(cbx.getValue()));
                    boolean local = m != null && m.path != null;
                    origin.setText(local ? "local" : "download");
                    originTip.setText(local ? m.path
                            : "Not present locally; --model will fetch it from HuggingFace.");
                };
                origin.setTooltip(originTip);
                cbx.valueProperty().addListener((o, ov, nv) -> refresh.run());
                refresh.run();
                HBox box = new HBox(8, cbx, origin);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                cell = box;
            } else if (anchor != null && input instanceof CheckBox cb) {
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

        // Auto-derived sections, collapsed so the dialog opens at core size.
        VBox sections = new VBox(4);
        sections.setPadding(new Insets(0, 8, 8, 8));
        for (Map.Entry<String, List<ParamSpec>> se : autoSections.entrySet()) {
            VBox content = new VBox(6);
            for (ParamSpec spec : se.getValue()) {
                Label lbl = new Label(spec.label + ":");
                lbl.setMinWidth(180);
                lbl.setPrefWidth(180);
                if (!spec.help.isBlank()) lbl.setTooltip(new Tooltip(spec.help));
                Node input = buildInput(spec);
                HBox row = new HBox(8, lbl, input);
                HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                content.getChildren().add(row);
                inputs.put(keyFor(spec), input);
            }
            javafx.scene.control.TitledPane tp = new javafx.scene.control.TitledPane(
                    sectionTitle(se.getKey()) + "  (" + se.getValue().size() + ")", content);
            ParamSpec master = sectionSwitches.get(se.getKey());
            if (master != null) {
                Node toggle = buildInput(master);
                inputs.put(keyFor(master), toggle);
                // Sits in the header so it stays visible while the section is
                // collapsed; the click must not also fold the section.
                toggle.setOnMouseClicked(javafx.event.Event::consume);
                if (!master.help.isBlank())
                    javafx.scene.control.Tooltip.install(toggle, new Tooltip(master.help));
                tp.setGraphic(toggle);
            }
            tp.setExpanded(false);
            sections.getChildren().add(tp);
        }
        // External inputs panel: collapsed by default — only the users running
        // wsinsight alongside HistoQC ever touch these. Inputs are registered
        // up-front so the last-used snapshot still has a place to land.
        if (!externalSpecs.isEmpty()) {
            VBox externalContent = new VBox(6);
            for (ParamSpec spec : externalSpecs) {
                Label lbl = new Label(spec.label + ":");
                lbl.setMinWidth(180);
                lbl.setPrefWidth(180);
                if (!spec.help.isBlank()) lbl.setTooltip(new Tooltip(spec.help));
                Node input = buildInput(spec);
                HBox row = new HBox(8, lbl, input);
                HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                externalContent.getChildren().add(row);
                inputs.put(keyFor(spec), input);
            }
            // The title counts the panel members so a future expansion of
            // EXTERNAL_INPUT_FLAGS surfaces a hint that the section grew.
            javafx.scene.control.TitledPane externalPanel = new javafx.scene.control.TitledPane(
                    "External inputs  (" + externalSpecs.size() + ")", externalContent);
            externalPanel.setExpanded(false);
            sections.getChildren().add(externalPanel);
        }

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

        // Commands like `run` expose ~100 params, which overflows the screen as
        // a plain form; cap the body and scroll instead.
        VBox scrollBody = new VBox(10, body, sections);
        javafx.scene.control.ScrollPane scroller = new javafx.scene.control.ScrollPane(scrollBody);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // prefHeight caps the size the dialog opens at; leaving maxHeight
        // unbounded is what lets it follow the window when resized.
        double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        scroller.setPrefHeight(preferredBodyHeight(scrollBody.prefHeight(-1) + 20, screenH));
        scroller.setMaxHeight(Double.MAX_VALUE);
        scroller.setMaxWidth(Double.MAX_VALUE);

        VBox content = !chainSpecs.isEmpty()
                ? new VBox(scopeBox, buildChainBox(inputs), scroller)
                : new VBox(scopeBox, scroller);
        VBox.setVgrow(scroller, javafx.scene.layout.Priority.ALWAYS);
        content.setFillWidth(true);
        pane.setContent(content);
        dialog.setResizable(true);

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
        //
        // 2026-08-23: write to project-scoped storage when a project is open,
        // so Project A's --results-dir doesn't leak into Project B's dialog.
        // Still also save to the user-wide LastUsedValues so single-image
        // (no-project) flows continue to remember.
        if (ProjectLastUsedValues.hasProject(project)) {
            ProjectLastUsedValues.save(project, subcommand, result);
        }
        LastUsedValues.save(subcommand, result);

        // 2026-08-23: also mirror --results-dir into the *shared* slot so
        // any other dialog openable from the same session (Import is the
        // main example) starts pre-filled with this value.
        String resultsDirValue = result.get(SharedResultsDir.KEY_PATH);
        if (resultsDirValue != null && !resultsDirValue.isBlank()) {
            SharedResultsDir.write(project, resultsDirValue);
        }

        // --- Results dir --------------------------------------------------
        // An explicit folder reuses whatever a previous run left there; blank
        // creates a fresh timestamped one.
        String requestedResults = result.get("--results-dir");
        java.io.File resultsDir = resolveResultsDir(
                requestedResults, () -> resolveHostResultsRoot(setupPre, project));
        if (resultsDir == null) {
            Dialogs.showErrorMessage("wsinsight",
                    requestedResults == null || requestedResults.isBlank()
                            ? "Could not create a results directory."
                            : "Not a usable results directory: " + requestedResults);
            return;
        }

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
            wsiDirOverride = scope.writeImageListIfNeeded(
                    resultsDir, !WSInsightSetup.getInstance().isUseNative());
        } catch (java.io.IOException e) {
            Dialogs.showErrorMessage("wsinsight",
                    "Failed to write slide list: " + e.getMessage());
            return;
        }

        // --- Build argv ---------------------------------------------------
        WSInsightSetup setup = WSInsightSetup.getInstance();
        boolean useNative = setup.isUseNative();

        DockerRunner.Builder rb = null;
        qupath.ext.wsinsight.runner.NativeRunner.Builder nb = null;
        // Translate against the runner's own mounts, so -v flags and rewritten
        // arguments can never disagree. A native run needs no translation at
        // all, which is why pm stays null there.
        qupath.ext.wsinsight.runner.PathMapper pm = null;
        if (useNative) {
            nb = qupath.ext.wsinsight.runner.NativeRunner.builder().fromSetup(setup);
        } else {
            rb = DockerRunner.builder().fromSetup(setup);
            rb.mount(new qupath.ext.wsinsight.runner.PathMapper.Mount(
                    slidesMountRoot.toPath(), "/slides"));
            rb.mount(new qupath.ext.wsinsight.runner.PathMapper.Mount(
                    resultsDir.toPath(), "/results"));
            pm = new qupath.ext.wsinsight.runner.PathMapper(rb.getMounts());
        }
        final DockerRunner.Builder dockerArgs = rb;
        final qupath.ext.wsinsight.runner.NativeRunner.Builder nativeArgs = nb;
        java.util.function.Consumer<String> addArg =
                a -> { if (dockerArgs != null) dockerArgs.arg(a); else nativeArgs.arg(a); };
        // Group-level options belong ahead of the subcommand: Click rejects
        // `wsinsight run --backend ...`.
        for (String a : globalArgs(setup.getWsiBackend())) addArg.accept(a);
        addArg.accept(subcommand);

        List<ParamSpec> missing = new ArrayList<>();
        for (ParamSpec spec : specs) {
            // Appended below with the resolved directory, not from the form.
            if (spec.flag != null && AUTO_PATH_FLAGS.containsKey(spec.flag))
                continue;
            String val = result.get(keyFor(spec));
            if (spec.required && (val == null || val.isBlank())) {
                missing.add(spec);
                continue;
            }
            if (val == null || val.isBlank()) continue;

            switch (spec.kind) {
                case BOOL_FLAG:
                    if ("true".equalsIgnoreCase(val)) addArg.accept(spec.flag);
                    break;
                case PATH:
                    String resolved = val;
                    if (spec.translatePath && pm != null) {
                        String mapped = pm.hostToContainer(val);
                        if (mapped != null) {
                            resolved = mapped;
                        } else if (!new File(val).exists()) {
                            // Not on this host, so it is already a container path
                            // (e.g. /app/zoo/... baked into the image).
                            resolved = val;
                        } else {
                            throw new IllegalStateException(
                                    "Path '" + val + "' is outside the slides and results "
                                    + "directories, so the container cannot see it.\n\n"
                                    + "Move it under one of them, or turn on "
                                    + "'Use native wsinsight' in Edit → Preferences → wsinsight.");
                        }
                    }
                    if (spec.flag != null) addArg.accept(spec.flag);
                    addArg.accept(resolved);
                    break;
                default:
                    // Model source is a preference, not a guess: falling back to
                    // --model silently costs five TLS retries before failing on a
                    // network that was never going to allow the download.
                    if ("--model".equals(spec.flag) && modelsByLabel.containsKey(val)) {
                        SchemaLoader.ModelSpec m = modelsByLabel.get(val);
                        if (!setup.isUseLocalModels()) {
                            addArg.accept(spec.flag);
                            addArg.accept(m.name);
                        } else if (m.path != null) {
                            addArg.accept("--zoo-model-dir");
                            addArg.accept(m.path);
                        } else {
                            throw new IllegalStateException(
                                    "'Use local model files' is on, but wsinsight reported no local "
                                    + "folder for '" + m.name + "'.\n\n"
                                    + "Regenerate the schema where the weights are visible:\n"
                                    + "    wsinsight describe --output <schema path>\n\n"
                                    + "or turn the preference off to download from HuggingFace.");
                        }
                        break;
                    }
                    if (spec.flag != null) addArg.accept(spec.flag);
                    for (String tok : tokensFor(spec, val)) addArg.accept(tok);
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
            String value = useNative
                    ? ("--wsi-dir".equals(e.getKey())
                            ? slidesMountRoot.getAbsolutePath()
                            : resultsDir.getAbsolutePath())
                    : e.getValue();
            if ("--wsi-dir".equals(e.getKey()) && wsiDirOverride != null) {
                value = wsiDirOverride;
            }
            addArg.accept(e.getKey());
            addArg.accept(value);
        }

        qupath.ext.wsinsight.runner.Runner runner;
        if (useNative) {
            if (!qupath.ext.wsinsight.runner.NativeRunner.isAvailable(setup.getNativeBinary())) {
                Dialogs.showErrorMessage("wsinsight",
                        "Could not run '" + setup.getNativeBinary() + " --version'.\n\n"
                        + "Set the executable under Edit → Preferences → wsinsight, "
                        + "or turn 'Use native wsinsight' off to use Docker.");
                return;
            }
            runner = nativeArgs.build();
        } else {
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
            runner = dockerArgs.build();
        }

        WSInsightProgressDialog progress = new WSInsightProgressDialog("wsinsight — " + subcommand, runner);
        // Imports are no longer triggered here. The user invokes them explicitly
        // through Extensions > wsinsight > Import results..., which lets them run
        // several wsinsight steps and import once at the end of a chain.
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

    /**
     * Split a value into the argv tokens the CLI expects.
     * <p>
     * click tuple types ({@code type=(int, int)}) need one token per element.
     * A bracketed value is also split even when {@code nargs} says 1, because
     * schemas generated before nargs was recorded — and values saved from them
     * — render such defaults as {@code "[2048,2048]"}. Comma-separated values
     * that are genuinely one argument (e.g. {@code --niche-leiden-res
     * 0.5,1.0,2.0}) are never bracketed, so they pass through intact.
     */
    static List<String> tokensFor(ParamSpec spec, String val) {
        String v = val.trim();
        boolean bracketed = v.length() > 1 && v.startsWith("[") && v.endsWith("]");
        if (spec.nargs <= 1 && !bracketed) return List.of(v);
        String inner = bracketed ? v.substring(1, v.length() - 1) : v;
        List<String> out = new ArrayList<>();
        for (String part : inner.split("[\\s,]+")) {
            String t = part.replaceAll("^[\"']|[\"']$", "").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? List.of(v) : out;
    }

    /**
     * Build the "Chain from previous run" subsection: a checkbox revealed by
     * default; when ticked, a column of optional directory pickers for each
     * chain-input flag becomes visible. Each picker's Node is registered in
     * {@code inputs} so the result converter emits the right argv tokens.
     */
    private VBox buildChainBox(Map<String, Node> inputs) {
        CheckBox toggle = new CheckBox(
                "Chain from previous run (region- or object-level results dir)");
        toggle.setSelected(false);
        toggle.setTooltip(new Tooltip(
                "Tick to feed an earlier wsinsight run's results back into this one. "
                        + "Use the region-inference directory when a previous patch-level "
                        + "(region) run's class probabilities should annotate object-level "
                        + "detections; use the object-inference directory when two object-level "
                        + "runs need to be co-registered."));

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(0, 0, 0, 24));
        for (ParamSpec s : chainSpecs) {
            Label lbl = new Label(s.label + ":");
            lbl.setMinWidth(180);
            lbl.setPrefWidth(180);
            if (!s.help.isBlank()) lbl.setTooltip(new Tooltip(s.help));
            Node input = buildInput(s);
            HBox row = new HBox(8, lbl, input);
            HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            rows.getChildren().add(row);
            inputs.put(keyFor(s), input);
        }
        rows.setVisible(false);
        rows.setManaged(false);
        toggle.selectedProperty().addListener((o, a, b) -> {
            rows.setVisible(b);
            rows.setManaged(b);
        });

        VBox box = new VBox(4, toggle, rows);
        box.setPadding(new Insets(0, 8, 8, 8));
        return box;
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
                if ("--model".equals(spec.flag) && !modelsByLabel.isEmpty()) {
                    box.getItems().addAll(modelsByLabel.keySet());
                    box.setValue(box.getItems().get(0));
                    Tooltip modelTip = new Tooltip();
                    box.valueProperty().addListener((o, ov, nv) -> {
                        SchemaLoader.ModelSpec m = modelsByLabel.get(nv);
                        String d = m == null || m.description.isBlank() ? nv : m.description;
                        modelTip.setText(d);
                    });
                    SchemaLoader.ModelSpec first = modelsByLabel.get(box.getValue());
                    modelTip.setText(first == null || first.description.isBlank()
                            ? box.getValue() : first.description);
                    box.setTooltip(modelTip);
                } else {
                    box.getItems().addAll(spec.choices);
                    if (!spec.defaultValue.isEmpty() && spec.choices.contains(spec.defaultValue))
                        box.setValue(spec.defaultValue);
                    else if (!spec.choices.isEmpty())
                        box.setValue(spec.choices.get(0));
                }
                // A ChoiceBox sizes to its widest item, which lets one long model
                // name stretch the whole dialog.
                box.setPrefWidth(280);
                box.setMaxWidth(Double.MAX_VALUE);
                return box;
            }
            case PATH: {
                TextField tf = new TextField(spec.defaultValue);
                tf.setPrefColumnCount(28);
                Button browse = new Button("…");
                browse.setTooltip(new Tooltip("Browse…"));
                browse.setOnAction(ev -> {
                    // --sptx-dir takes a `sptx-list://<abs path>` URI whose
                    // payload is a manifest.tsv file; the directory/heuristic
                    // branch below would mangle it, so handle this one flag
                    // explicitly before falling back.
                    if ("--sptx-dir".equals(spec.flag)) {
                        FileChooser fc = new FileChooser();
                        fc.getExtensionFilters().add(
                                new FileChooser.ExtensionFilter("TSV (Xenium manifest)", "*.tsv"));
                        if (!tf.getText().isBlank()) {
                            String stripped = tf.getText().startsWith("sptx-list://")
                                    ? tf.getText().substring("sptx-list://".length())
                                    : tf.getText();
                            File init = new File(stripped);
                            if (init.getParentFile() != null && init.getParentFile().isDirectory())
                                fc.setInitialDirectory(init.getParentFile());
                        }
                        File f = fc.showOpenDialog(null);
                        if (f != null) tf.setText("sptx-list://" + f.getAbsolutePath());
                        return;
                    }
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

    /**
     * Resolve the host directory that will be bind-mounted at {@code /results}.
     * When a QuPath project is open and its base directory is on the local
     * file system, results land under
     * {@code <project>/wsinsight-runs/<subcommand>-<timestamp>/} so they
     * travel with the project and can be re-inspected later. Otherwise, a
     * fresh scratch directory under the system temp folder is created.
     */
    /**
     * The folder the user asked for, or {@code fallback} when they left the
     * field blank. Null means the requested path could not be used.
     */
    static File resolveResultsDir(String requested, java.util.function.Supplier<File> fallback) {
        if (requested == null || requested.isBlank())
            return fallback.get();
        File dir = new File(requested.trim());
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

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
