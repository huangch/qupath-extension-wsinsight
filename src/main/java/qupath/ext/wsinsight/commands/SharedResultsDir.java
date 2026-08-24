package qupath.ext.wsinsight.commands;

import java.io.File;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.projects.Project;

/**
 * Shared, single-value memory for the {@code --results-dir} (and any other
 * command-agnostic results-path) the user last picked in any WSInsight
 * dialog (Run / Infer / Patch / Export / Import results).
 *
 * <p>The flag is intentionally command-agnostic: a user who opens the Run
 * dialog with one project, picks {@code /scratch/run-2026-08-22}, then
 * immediately opens the Import Results dialog should not have to re-pick
 * the same folder. Earlier layers
 * ({@link LastUsedValues} / {@link ProjectLastUsedValues}) keyed by
 * subcommand, which forced the user to re-select the path the first time
 * they reached a different dialog.
 *
 * <p>Storage layout (mirrors the layered scheme of {@link LastUsedValues}
 * / {@link ProjectLastUsedValues}):
 *
 * <ul>
 *   <li>Project-scoped: {@code wsinsight/shared/<project-digest>/<key>}
 *   <li>User-wide fallback: {@code wsinsight/shared/<key>}
 * </ul>
 *
 * <p>Reads check the project layer first, then fall back to the user-wide
 * layer; writes go to whichever layer is appropriate (project if a
 * project is open, otherwise user-wide), and <em>also</em> mirror to the
 * user-wide layer so a user opening a different project still sees their
 * most recently chosen folder as the initial suggestion.
 *
 * <p>2026-08-23: introduced to unify {@code --results-dir} memory across
 * the per-run and standalone-import dialogs.
 */
public final class SharedResultsDir {

    private static final Logger logger = LoggerFactory.getLogger(
            SharedResultsDir.class);
    private static final String ROOT_NODE = "wsinsight/shared";
    /** Preference key under {@link #ROOT_NODE}; reserved single value. */
    public static final String KEY_PATH = "--results-dir";

    private SharedResultsDir() {}

    /**
     * Read the most recent value for {@link #KEY_PATH}.
     *
     * <p>Returns an empty {@link Optional} when nothing is stored, the
     * stored value is blank, or the path no longer exists on disk at read
     * time. Callers should treat empty as "nothing to pre-fill".
     */
    public static Optional<String> read(Project<?> project) {
        // Read order: project layer first, then user-wide.
        String fromProject = read(prefsForProject(project));
        if (fromProject != null) {
            return Optional.of(fromProject);
        }
        String fromUser = read(PathPrefs.getUserPreferences().node(ROOT_NODE));
        if (fromUser != null) {
            return Optional.of(fromUser);
        }
        return Optional.empty();
    }

    /**
     * Persist a new value. A {@code null} or blank {@code path} removes
     * the stored value (so a stale entry can't survive a manual delete).
     *
     * <p>Writes go to the project layer when a project is open, and always
     * also to the user-wide layer (so opening a different project later
     * still surfaces the most recent value as a starting suggestion).
     */
    public static void write(Project<?> project, String path) {
        String trimmed = path == null ? null : path.trim();
        // User-wide always (mirror), so cross-project reuse is possible.
        writeValue(PathPrefs.getUserPreferences().node(ROOT_NODE), trimmed);
        // Project-layer addendum when a project is open.
        if (ProjectLastUsedValues.hasProject(project)) {
            writeValue(prefsForProject(project), trimmed);
        }
    }

    /** Clear the project-scoped value, leaving the user-wide value intact. */
    public static void clearProject(Project<?> project) {
        if (!ProjectLastUsedValues.hasProject(project)) return;
        try {
            prefsForProject(project).remove(KEY_PATH);
            prefsForProject(project).flush();
        } catch (BackingStoreException e) {
            logger.debug("Could not clear project results-dir: {}", e.toString());
        }
    }

    // ---- helpers -------------------------------------------------------

    private static Preferences prefsForProject(Project<?> project) {
        // ProjectLastUsedValues exposes the project-key derivation only to
        // its own helpers, so we reuse its layout through a tiny duplicate:
        // wsinsight/shared/<project-digest>
        try {
            File base = qupath.lib.projects.Projects.getBaseDirectory(project);
            if (base == null) {
                return PathPrefs.getUserPreferences().node(ROOT_NODE);
            }
            String abs = base.getAbsoluteFile().toString();
            String leaf = leafFromAbsPath(abs);
            if (leaf == null) {
                return PathPrefs.getUserPreferences().node(ROOT_NODE);
            }
            return PathPrefs.getUserPreferences().node(ROOT_NODE).node(leaf);
        } catch (Exception e) {
            return PathPrefs.getUserPreferences().node(ROOT_NODE);
        }
    }

    /** Same 16-hex + last-16-chars scheme as ProjectLastUsedValues.projectKey. */
    private static String leafFromAbsPath(String abs) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(abs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            sb.append("-").append(abs.replaceAll("[^A-Za-z0-9]", "_")
                    .substring(Math.max(0, abs.length() - 16)));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(Preferences prefs) {
        try {
            String v = prefs.get(KEY_PATH, null);
            if (v == null || v.isBlank()) return null;
            if (!new File(v).isDirectory()) {
                logger.debug("Skipping stale shared results-dir {} (no longer a directory)", v);
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeValue(Preferences prefs, String trimmed) {
        try {
            if (trimmed == null || trimmed.isBlank()) {
                prefs.remove(KEY_PATH);
            } else {
                prefs.put(KEY_PATH, trimmed);
            }
            prefs.flush();
        } catch (BackingStoreException e) {
            logger.debug("Could not write shared results-dir: {}", e.toString());
        }
    }
}
