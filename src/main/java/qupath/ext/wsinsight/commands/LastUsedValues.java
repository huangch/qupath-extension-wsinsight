package qupath.ext.wsinsight.commands;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persists and restores the last values the user entered in a
 * {@link GenericCommandDialog} form, on a per-subcommand basis, so that
 * re-opening the same subcommand's dialog seeds the widgets with their
 * previous input rather than the schema defaults.
 *
 * <p>Backed by QuPath's Java {@link Preferences} node (same store as
 * {@link PathPrefs#getUserPreferences()}) under the fixed subtree
 * {@code wsinsight/last/&lt;subcommand&gt;/}. Keys within that subtree are
 * the flag strings (e.g. {@code --model}) or, for positional arguments,
 * the {@link ParamSpec#label}.
 *
 * <p>Empty values are removed rather than stored to avoid clobbering schema
 * defaults with empty strings on subsequent opens.
 */
public final class LastUsedValues {

    private static final Logger logger = LoggerFactory.getLogger(LastUsedValues.class);
    private static final String ROOT_NODE = "wsinsight/last";

    private LastUsedValues() {}

    /**
     * Forget every remembered value across all subcommands.
     *
     * @return number of subcommands whose stored values were removed
     */
    public static int clearAll() {
        try {
            Preferences root = PathPrefs.getUserPreferences().node(ROOT_NODE);
            int n = root.childrenNames().length;
            root.removeNode();
            PathPrefs.getUserPreferences().flush();
            return n;
        } catch (BackingStoreException e) {
            logger.debug("Could not clear last-used values: {}", e.toString());
            return 0;
        }
    }

    /** @return previously saved flag → value map for {@code subcommand}; empty if none. */
    public static Map<String, String> load(String subcommand) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Preferences node = node(subcommand);
            for (String key : node.keys()) {
                String v = node.get(key, null);
                if (v != null && !v.isEmpty()) out.put(key, v);
            }
        } catch (BackingStoreException e) {
            logger.debug("Could not read last-used values for {}: {}", subcommand, e.toString());
        }
        return out;
    }

    /** Persist {@code values} (flag → textual value) as the new last-used state. */
    public static void save(String subcommand, Map<String, String> values) {
        try {
            Preferences node = node(subcommand);
            // Remove entries absent from the incoming snapshot so stale prefs
            // don't resurrect when a flag is later unset or emptied.
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
            logger.debug("Could not save last-used values for {}: {}", subcommand, e.toString());
        }
    }

    private static Preferences node(String subcommand) {
        return PathPrefs.getUserPreferences().node(ROOT_NODE).node(subcommand);
    }
}
