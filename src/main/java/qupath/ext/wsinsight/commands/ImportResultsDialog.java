package qupath.ext.wsinsight.commands;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Independent dialog for re-importing WSInsight results into the active
 * QuPath project, decoupled from any per-run dialog. Users reach this from
 * the Extensions > WSInsight menu and pick a results directory produced by
 * a previous wsinsight run; the directory is then scanned for GeoJSON
 * outputs and the matching project images are updated.
 * <p>
 * This dialog replaces the per-run auto-import checkbox that used to fire
 * on success. Letting the user invoke import explicitly gives them a single
 * place to merge N runs worth of outputs (e.g. when running wsinsight
 * step-by-step: patch → infer → hplot → niche → import once at the end).
 */
public class ImportResultsDialog {

    private final QuPathGUI gui;

    public ImportResultsDialog(QuPathGUI gui) {
        this.gui = gui;
    }

    /**
     * Show the dialog. On OK, walks the chosen results directory and pushes
     * any GeoJSON annotations back into the matching project image(s).
     */
    public void showAndImport() {
        Project<?> project = gui != null ? gui.getProject() : null;
        boolean haveProject = project != null && !project.getImageList().isEmpty();
        RunScope currentScope = RunScope.fromCurrentImage(gui);
        if (currentScope == null && !haveProject) {
            Dialogs.showErrorMessage("wsinsight",
                    "No image available. Open a slide or open a project with images.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("wsinsight — Import results");
        dialog.setHeaderText("Import WSInsight results into the active QuPath project");
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // --- Previously-used results directory ----------------------------
        // 2026-08-23 pre-populate: the standalone Import dialog used to start
        // with a blank path even though the run dialog remembers the last
        // --results-dir via LastUsedValues / ProjectLastUsedValues. Mirror
        // the run-dialog preference layering (project-scoped wins when a
        // project is open, otherwise fall back to user-wide). The subcommand
        // key is "import" so per-run writes don't clobber the import dialog's
        // memory.
        Map<String, String> lastUsed;
        if (ProjectLastUsedValues.hasProject(project)) {
            lastUsed = ProjectLastUsedValues.load(project, "import");
        } else {
            lastUsed = LastUsedValues.load("import");
        }
        String initialResultsDir = lastUsed.get("--results-dir");

        // --- Results directory selector -----------------------------------
        TextField dirField = new TextField();
        dirField.setPrefColumnCount(40);
        if (initialResultsDir != null && new File(initialResultsDir).isDirectory()) {
            dirField.setText(initialResultsDir);
        }
        Button browse = new Button("Browse…");
        browse.setOnAction(ev -> {
            DirectoryChooser dc = new DirectoryChooser();
            if (!dirField.getText().isBlank()) {
                File init = new File(dirField.getText());
                if (init.isDirectory()) dc.setInitialDirectory(init);
                else if (init.getParentFile() != null && init.getParentFile().isDirectory())
                    dc.setInitialDirectory(init.getParentFile());
            }
            File f = dc.showDialog(dialog.getOwner());
            if (f != null) dirField.setText(f.getAbsolutePath());
        });
        HBox dirRow = new HBox(8, new Label("Results directory:"), dirField, browse);
        dirRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(dirField, javafx.scene.layout.Priority.ALWAYS);

        // --- Scope (which images get the imported objects) ----------------
        ToggleGroup scopeGroup = new ToggleGroup();
        RadioButton rbCurrent = new RadioButton("Current open image");
        RadioButton rbSelection = new RadioButton("Selected project images…");
        rbCurrent.setToggleGroup(scopeGroup);
        rbSelection.setToggleGroup(scopeGroup);
        rbCurrent.setDisable(currentScope == null);
        rbSelection.setDisable(!haveProject);

        final java.util.List<ProjectImageEntry<?>> pickedEntries = new java.util.ArrayList<>();
        rbSelection.setOnAction(ev -> {
            if (project == null) return;
            java.util.List<ProjectImageEntry<?>> picked =
                    pickProjectEntries(project, pickedEntries);
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

        HBox scopeRow = new HBox(12,
                new Label("Import into:"), rbCurrent, rbSelection);
        scopeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Tooltip scopeTip = new Tooltip(
                "Choose which images in the active project should receive the "
                        + "WSInsight annotations. 'Current open image' loads them "
                        + "into the image you have visible right now; "
                        + "'Selected project images' applies them to a free-form "
                        + "subset you pick from the project.");
        rbCurrent.setTooltip(scopeTip);
        rbSelection.setTooltip(scopeTip);

        VBox content = new VBox(10, dirRow, scopeRow);
        content.setPadding(new Insets(8));
        pane.setContent(content);
        dialog.setResizable(true);

        // Disable OK until the user supplies a usable results directory.
        Button ok = (Button) pane.lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            String t = dirField.getText();
            boolean okDir = t != null && !t.isBlank() && new File(t).isDirectory();
            if (ok != null) ok.setDisable(!okDir);
        };
        dirField.textProperty().addListener((o, a, b) -> validate.run());
        validate.run();

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            File resultsDir = new File(dirField.getText());
            if (!resultsDir.isDirectory()) {
                Dialogs.showErrorMessage("wsinsight",
                        "Results directory does not exist:\n" + resultsDir);
                return;
            }
            RunScope scope;
            if (rbCurrent.isSelected()) {
                scope = currentScope;
            } else {
                if (pickedEntries.isEmpty()) return;
                scope = RunScope.fromProjectSelection(pickedEntries);
            }
            if (scope == null) {
                Dialogs.showErrorMessage("wsinsight", "No image selected.");
                return;
            }
            // 2026-08-23: remember the chosen results dir under the same
            // "import" preference key we read at dialog open time, so the
            // next time the user opens this dialog it pre-fills with the
            // last value. Both layers (project, user-wide) are written,
            // matching the run dialog.
            Map<String, String> toSave = new LinkedHashMap<>();
            toSave.put("--results-dir", resultsDir.getAbsolutePath());
            if (ProjectLastUsedValues.hasProject(project)) {
                ProjectLastUsedValues.save(project, "import", toSave);
            }
            LastUsedValues.save("import", toSave);
            AutoImport.importResults(resultsDir, scope, project);
        });
    }

    /**
     * Open the same multi-select picker used by the run dialog so users
     * familiar with that workflow hit the same UX here.
     */
    private java.util.List<ProjectImageEntry<?>> pickProjectEntries(
            Project<?> project,
            java.util.List<ProjectImageEntry<?>> preSelected) {
        Dialog<java.util.List<ProjectImageEntry<?>>> dlg = new Dialog<>();
        dlg.setTitle("WSInsight — Select project images");
        dlg.setHeaderText("Choose the images that should receive the imported results:");
        DialogPane dp = dlg.getDialogPane();
        dp.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        javafx.collections.ObservableList<ProjectImageEntry<?>> items =
                javafx.collections.FXCollections.observableArrayList(project.getImageList());
        java.util.Set<ProjectImageEntry<?>> checked =
                new java.util.HashSet<>(preSelected);

        javafx.scene.control.ListView<ProjectImageEntry<?>> lv =
                new javafx.scene.control.ListView<>(items);
        lv.setPrefSize(480, 360);
        lv.setCellFactory(javafx.scene.control.cell.CheckBoxListCell.forListView(
                entry -> {
                    javafx.beans.property.SimpleBooleanProperty prop =
                            new javafx.beans.property.SimpleBooleanProperty(checked.contains(entry));
                    prop.addListener((obs, was, now) -> {
                        if (now) checked.add(entry); else checked.remove(entry);
                    });
                    return prop;
                },
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
        dp.setContent(new VBox(6, lv, actions));
        dlg.setResizable(true);

        dlg.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            java.util.List<ProjectImageEntry<?>> out = new java.util.ArrayList<>();
            for (ProjectImageEntry<?> e : items) {
                if (checked.contains(e)) out.add(e);
            }
            return out;
        });
        return dlg.showAndWait().orElse(null);
    }
}
