package qupath.ext.wsinsight.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@link GenericCommandDialog} instances, driven by the CLI schema
 * that {@code wsinsight schema --output <path>} writes. Reading it from disk
 * keeps menu creation instantaneous (no Docker call at startup) and leaves the
 * CLI as the single generator of that file.
 */
public final class WSInsightCommands {

    private static final Logger logger = LoggerFactory.getLogger(WSInsightCommands.class);

    private static SchemaLoader cachedSchema;

    private WSInsightCommands() {}

    public static synchronized SchemaLoader schema() throws IOException {
        if (cachedSchema == null) {
            String path = qupath.ext.wsinsight.WSInsightSetup.getInstance().getCliSchemaPath();
            if (path == null || path.isBlank())
                path = SchemaLoader.DEFAULT_PATH;
            cachedSchema = SchemaLoader.fromFile(java.nio.file.Path.of(path));
            logger.info("Loaded WSInsight CLI schema from {} (wsinsight {})",
                    path, cachedSchema.wsinsightVersion());
        }
        return cachedSchema;
    }

    /** Drop the cached schema so the next {@link #schema()} re-reads the file. */
    public static synchronized void reset() {
        cachedSchema = null;
    }

    /** @return command name → factory, in stable menu order. */
    public static Map<String, Supplier<GenericCommandDialog>> all() throws IOException {
        Map<String, Supplier<GenericCommandDialog>> out = new LinkedHashMap<>();
        SchemaLoader s = schema();
        for (String name : orderedNames(s.commandNames())) {
            String title = "wsinsight — " + name;
            List<ParamSpec> specs = s.specsFor(name);
            Map<String, SchemaLoader.GroupSpec> groups = s.groupsFor(name);
            out.put(name, () -> new GenericCommandDialog(title, name, specs, groups));
        }
        return out;
    }

    private static List<String> orderedNames(List<String> raw) {
        List<String> primary = List.of(
                "run", "patch", "infer", "reg",
                "ncomp", "ecomp", "tcomp", "niche", "niche-profile",
                "agg", "import", "hplot", "hplot-finalize", "export");
        List<String> out = new ArrayList<>();
        for (String p : primary) if (raw.contains(p)) out.add(p);
        for (String n : raw) if (!out.contains(n)) out.add(n);
        return out;
    }
}
