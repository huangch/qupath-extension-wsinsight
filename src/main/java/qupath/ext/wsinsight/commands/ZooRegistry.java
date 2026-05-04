package qupath.ext.wsinsight.commands;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a WSInsight zoo registry JSON file (as referenced by the
 * {@code WSINSIGHT_ZOO_REGISTRY_PATH} preference) and enumerates the
 * model folders that exist on disk next to it.
 *
 * <p>Registry schema (minimal):
 * <pre>{@code
 * {
 *   "models": {
 *     "<name>": {
 *       "description": "…",
 *       "hf_repo_id": "<owner>/<repo>",
 *       "hf_revision": "main"
 *     }, …
 *   }
 * }
 * }</pre>
 *
 * <p>For each entry, the model folder is expected at
 * {@code <registry-dir>/<owner>/<repo>/<revision>/} and must contain
 * {@code config.json} and {@code torchscript_model.pt}. Entries whose folder
 * is missing are skipped so the QuPath dropdown only shows usable models.
 *
 * <p>The display label is resolved in this order:
 * <ol>
 *   <li>{@code display_name} / {@code name} / {@code description} from
 *       the model's {@code config.json}</li>
 *   <li>{@code description} from the registry entry</li>
 *   <li>the registry key itself</li>
 * </ol>
 */
public final class ZooRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ZooRegistry.class);

    public static final class Entry {
        /** Registry key, e.g. "CellViT-SAM-H-x40". */
        public final String key;
        /** Human-readable label for the dropdown. */
        public final String displayLabel;
        /** Absolute host path to the model folder (contains config.json + torchscript_model.pt). */
        public final Path hostDir;

        public Entry(String key, String displayLabel, Path hostDir) {
            this.key = key;
            this.displayLabel = displayLabel;
            this.hostDir = hostDir;
        }
    }

    private ZooRegistry() {}

    /**
     * Load and validate the registry referenced by {@code registryPath}.
     *
     * @return list of usable entries (empty if {@code registryPath} is null/blank,
     *         the file is missing, the JSON is malformed, or no model folders exist).
     */
    public static List<Entry> load(String registryPath) {
        if (registryPath == null || registryPath.isBlank())
            return List.of();
        Path json = Path.of(registryPath);
        if (!Files.isRegularFile(json)) {
            logger.debug("Zoo registry file not found: {}", json);
            return List.of();
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            root = new Gson().fromJson(r, JsonObject.class);
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            logger.warn("Failed to read zoo registry {}: {}", json, e.toString());
            return List.of();
        }
        if (root == null || !root.has("models") || !root.get("models").isJsonObject())
            return List.of();

        Path registryDir = json.toAbsolutePath().getParent();
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("models").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject m = e.getValue().getAsJsonObject();
            String repoId = str(m, "hf_repo_id");
            String revision = str(m, "hf_revision");
            if (repoId == null || revision == null) continue;

            Path modelDir = registryDir.resolve(repoId).resolve(revision);
            Path cfg = modelDir.resolve("config.json");
            if (!Files.isRegularFile(cfg)) {
                logger.debug("Skipping '{}' — no config.json at {}", e.getKey(), cfg);
                continue;
            }

            String label = labelFromConfig(cfg);
            if (label == null) label = str(m, "description");
            if (label == null || label.isBlank()) label = e.getKey();

            out.add(new Entry(e.getKey(), label, modelDir));
        }
        out.sort((a, b) -> a.displayLabel.compareToIgnoreCase(b.displayLabel));
        return out;
    }

    private static String labelFromConfig(Path configJson) {
        try (Reader r = Files.newBufferedReader(configJson, StandardCharsets.UTF_8)) {
            JsonObject o = new Gson().fromJson(r, JsonObject.class);
            if (o == null) return null;
            for (String key : new String[] {"display_name", "name", "title", "description"}) {
                String v = str(o, key);
                if (v != null && !v.isBlank()) return v;
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            logger.debug("Could not read {}: {}", configJson, e.toString());
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() || !el.isJsonPrimitive() ? null : el.getAsString();
    }
}
