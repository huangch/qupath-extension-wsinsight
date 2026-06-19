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
 * Factory for {@link GenericCommandDialog} instances, driven by the bundled
 * CLI schema produced by {@code wsinsight describe --json}. This means the
 * QuPath menu structure stays in lock-step with the Python CLI without any
 * Docker calls at startup.
 */
public final class WSInsightCommands {

    private static final Logger logger = LoggerFactory.getLogger(WSInsightCommands.class);

    private static SchemaLoader cachedSchema;

    private WSInsightCommands() {}

    public static synchronized SchemaLoader schema() {
        if (cachedSchema == null) {
            try {
                cachedSchema = SchemaLoader.fromBundled();
            } catch (IOException e) {
                logger.error("Failed to load bundled WSInsight CLI schema", e);
                throw new IllegalStateException("WSInsight CLI schema not available", e);
            }
        }
        return cachedSchema;
    }

    /** @return command name → factory, in stable menu order. */
    public static Map<String, Supplier<GenericCommandDialog>> all() {
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
                "ncomp", "ecomp", "tcomp", "cme", "cme-profile",
                "hplot", "hplot-finalize", "export");
        List<String> out = new ArrayList<>();
        for (String p : primary) if (raw.contains(p)) out.add(p);
        for (String n : raw) if (!out.contains(n)) out.add(n);
        return out;
    }
}
