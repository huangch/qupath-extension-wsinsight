package qupath.ext.wsinsight.commands;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.projects.Project;

/**
 * Persists and restores the last values the user entered in a
 * {@link GenericCommandDialog} form, scoped to a single open QuPath project.
 *
 * <p>Companion to {@link LastUsedValues}, which is global across projects.
 * Project-scoped memory lets users keep separate {@code --results-dir}
 * conventions across different projects without leaking one project's
 * scratch output into another.
 *
 * <p>The project key is derived from the project base directory's
 * absolute path (via {@link qupath.lib.projects.Projects#getBaseDirectory}).
 * A short SHA-256 prefix is used as the leaf node so very long paths, or
 * paths containing characters disallowed in {@link Preferences} keys,
 * still produce a stable address.
 *
 * <p>Backed by QuPath's Java {@link Preferences} store (same node as
 * {@link PathPrefs#getUserPreferences()}) under the subtree
 * {@code wsinsight/project/&lt;digest&gt;/&lt;subcommand&gt;/}.
 *
 * <p>2026-08-23: created to honour per-project results-dir memory request.
 */
public final class ProjectLastUsedValues {

    private static final Logger logger = LoggerFactory.getLogger(
            ProjectLastUsedValues.class);
    private static final String ROOT_NODE = "wsinsight/project";

    private ProjectLastUsedValues() {}

    /**
     * Returns true when the running code is bound to a QuPath project that
     * has a resolvable base directory. Callers should use this to decide
     * whether to call {@link #load} / {@link #save} or fall back to
     * {@link LastUsedValues}.
     */
    public static boolean hasProject(Project<?> project) {
        if (project == null) return false;
        try {
            File base = qupath.lib.projects.Projects.getBaseDirectory(project);
            return base != null && base.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Forget every remembered value for this project, across all subcommands,
     * so the next dialog starts from the wsinsight defaults again.
     *
     * @return number of subcommands whose stored values were removed
     */
    public static int clearAll(Project<?> project) {
        if (!hasProject(project)) return 0;
        String key = projectKey(project);
        if (key == null) return 0;
        try {
            Preferences root = PathPrefs.getUserPreferences().node(ROOT_NODE);
            if (!root.nodeExists(key)) return 0;
            Preferences node = root.node(key);
            int n = node.childrenNames().length;
            node.removeNode();
            root.flush();
            return n;
        } catch (BackingStoreException e) {
            logger.debug("Could not clear project last-used values: {}", e.toString());
            return 0;
        }
    }

    /** Load last-used values for this project's {@code subcommand}. */
    public static Map<String, String> load(Project<?> project, String subcommand) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!hasProject(project)) return out;
        String key = projectKey(project);
        if (key == null) return out;
        try {
            Preferences node = PathPrefs.getUserPreferences()
                    .node(ROOT_NODE).node(key).node(subcommand);
            for (String k : node.keys()) {
                String v = node.get(k, null);
                if (v != null && !v.isEmpty()) out.put(k, v);
            }
        } catch (BackingStoreException e) {
            logger.debug("Could not read project last-used values for {}: {}",
                    subcommand, e.toString());
        }
        return out;
    }

    /** Persist values for this project's {@code subcommand}; null/empty removes. */
    public static void save(Project<?> project, String subcommand,
                             Map<String, String> values) {
        if (!hasProject(project)) return;
        String key = projectKey(project);
        if (key == null) return;
        try {
            Preferences node = PathPrefs.getUserPreferences()
                    .node(ROOT_NODE).node(key).node(subcommand);
            for (String existing : node.keys()) {
                if (!values.containsKey(existing)) node.remove(existing);
            }
            for (Map.Entry<String, String> e : values.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isEmpty()) node.remove(e.getKey());
                else node.put(e.getKey(), v);
            }
            node.flush();
        } catch (BackingStoreException e) {
            logger.debug("Could not save project last-used values for {}: {}",
                    subcommand, e.toString());
        }
    }

    /**
     * Stable preference-key substring from a project's base directory.
     * Returns null when no base directory is available.
     */
    private static String projectKey(Project<?> project) {
        try {
            File base = qupath.lib.projects.Projects.getBaseDirectory(project);
            if (base == null) return null;
            String abs = base.getAbsoluteFile().toString();
            // First 16 hex chars of SHA-256 are enough to disambiguate project
            // base paths across a host while keeping the prefs tree shallow.
            java.security.MessageDigest md;
            md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(abs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            // Tag the leaf with a short human-readable suffix so users browsing
            // the registry can still tell which project is which.
            sb.append("-").append(abs.replaceAll("[^A-Za-z0-9]", "_")
                    .substring(Math.max(0, abs.length() - 16)));
            return sb.toString();
        } catch (Exception e) {
            logger.debug("Could not derive project key: {}", e.toString());
            return null;
        }
    }
}
