package qupath.ext.wsinsight.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the WSInsight CLI schema (produced by {@code wsinsight describe --json})
 * from the extension resources or from a user-supplied path, and converts each
 * described subcommand into a list of {@link ParamSpec}s suitable for
 * {@link GenericCommandDialog}.
 * <p>
 * Bundling the schema inside the jar keeps menu creation instantaneous
 * (no Docker call at startup) while still letting us stay in sync with the
 * Python CLI — the schema is regenerated and re-bundled at every release.
 */
public final class SchemaLoader {

    private static final Logger logger = LoggerFactory.getLogger(SchemaLoader.class);
    private static final String BUNDLED_RESOURCE = "/wsinsight-cli-schema.json";

    private final JsonObject root;

    private SchemaLoader(JsonObject root) {
        this.root = root;
    }

    public static SchemaLoader fromBundled() throws IOException {
        InputStream in = SchemaLoader.class.getResourceAsStream(BUNDLED_RESOURCE);
        if (in == null)
            throw new IOException("Bundled CLI schema not found on classpath: " + BUNDLED_RESOURCE);
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new SchemaLoader(new Gson().fromJson(r, JsonObject.class));
        }
    }

    public static SchemaLoader fromFile(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new SchemaLoader(new Gson().fromJson(r, JsonObject.class));
        }
    }

    /** Command names in the schema, sorted for stable menu order. */
    public List<String> commandNames() {
        List<String> names = new ArrayList<>();
        JsonObject cmds = root.getAsJsonObject("commands");
        if (cmds == null) return names;
        names.addAll(new TreeMap<>(cmds.asMap()).keySet());
        return names;
    }

    public String commandHelp(String name) {
        JsonObject cmd = command(name);
        if (cmd == null) return "";
        JsonElement h = cmd.get("help");
        return h != null && !h.isJsonNull() ? h.getAsString() : "";
    }

    public List<ParamSpec> specsFor(String name) {
        JsonObject cmd = command(name);
        if (cmd == null) return List.of();
        JsonArray params = cmd.getAsJsonArray("params");
        if (params == null) return List.of();
        List<ParamSpec> out = new ArrayList<>(params.size());
        for (JsonElement el : params) {
            ParamSpec s = toSpec(el.getAsJsonObject());
            if (s != null) out.add(s);
        }
        return out;
    }

    private JsonObject command(String name) {
        JsonObject cmds = root.getAsJsonObject("commands");
        return cmds == null ? null : cmds.getAsJsonObject(name);
    }

    private static ParamSpec toSpec(JsonObject p) {
        String kind = str(p, "kind");
        boolean isFlag = bool(p, "is_flag", false);
        boolean isOption = "option".equals(str(p, "param_type"));
        String flag = firstLongFlag(p);
        String label = deriveLabel(p, flag);
        String help = str(p, "help");
        String defaultValue = defaultAsString(p);
        boolean required = bool(p, "required", false);

        ParamSpec.Kind specKind;
        List<String> choices = null;
        boolean translatePath = false;

        if (isFlag || "bool".equalsIgnoreCase(kind)) {
            specKind = ParamSpec.Kind.BOOL_FLAG;
        } else if ("choice".equalsIgnoreCase(kind) && p.has("choices")) {
            specKind = ParamSpec.Kind.CHOICE;
            choices = new ArrayList<>();
            for (JsonElement c : p.getAsJsonArray("choices"))
                choices.add(c.getAsString());
        } else if ("path".equalsIgnoreCase(kind)
                || isPathByName(str(p, "name"))) {
            // Either Click declared it as a path, or the option name looks
            // like a filesystem path (e.g. --wsi-dir / --results-dir, which
            // use the custom URIPath type and therefore come through the
            // schema as "string"). Treat both as PATH so the dialog renders
            // a Browse button.
            specKind = ParamSpec.Kind.PATH;
            // Paths referring to slides/results directories should be mapped
            // host↔container; locally-generated outputs outside those mounts
            // should not be. We can't know from the schema alone, so apply a
            // name-based heuristic: anything matching /wsi|dir|path|output|file/
            // is translated. The user can still override via extra mounts.
            String n = str(p, "name");
            translatePath = n != null && n.toLowerCase(Locale.ROOT)
                    .matches(".*(wsi|dir|path|output|file|manifest|registry|results|slides).*");
        } else if ("int".equalsIgnoreCase(kind)) {
            specKind = ParamSpec.Kind.INT;
        } else if ("float".equalsIgnoreCase(kind)) {
            specKind = ParamSpec.Kind.DOUBLE;
        } else {
            specKind = ParamSpec.Kind.STRING;
        }

        ParamSpec.Builder b = ParamSpec.builder()
                .flag(isOption ? flag : null)
                .label(label)
                .help(help)
                .kind(specKind)
                .defaultValue(defaultValue)
                .required(required)
                .group(str(p, "group"))
                .columnBreak(bool(p, "column_break", false));
        if (choices != null) b.choices(choices);
        if (translatePath) b.translatePath(true);
        return b.build();
    }

    /**
     * Name-based detection of path-like options whose Click type is a custom
     * path wrapper (e.g. {@code URIPath}) and therefore reported as
     * {@code "string"} in the schema. Matches names ending in
     * {@code _dir} / {@code _path} / {@code _file} or containing
     * {@code wsi_dir} / {@code results_dir}.
     */
    private static boolean isPathByName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith("_dir") || n.endsWith("_path") || n.endsWith("_file")
                || n.equals("dir") || n.equals("path") || n.equals("file")
                || n.contains("wsi_dir") || n.contains("results_dir")
                || n.contains("output_dir") || n.contains("input_dir")
                || n.contains("cache_dir") || n.contains("manifest")
                || n.contains("registry");
    }

    private static String firstLongFlag(JsonObject p) {        if (!p.has("flags")) return null;
        JsonArray arr = p.getAsJsonArray("flags");
        String first = null;
        for (JsonElement el : arr) {
            String s = el.getAsString();
            if (s.startsWith("--")) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private static String deriveLabel(JsonObject p, String flag) {
        String name = str(p, "name");
        if (flag != null) {
            String raw = flag.replaceFirst("^--", "").replaceFirst("^-", "");
            return prettify(raw);
        }
        return name == null ? "value" : prettify(name);
    }

    private static String prettify(String raw) {
        String s = raw.replace('_', ' ').replace('-', ' ').trim();
        return s.isEmpty() ? raw : s;
    }

    private static String defaultAsString(JsonObject p) {
        JsonElement d = p.get("default");
        if (d == null || d.isJsonNull()) return "";
        if (d.isJsonPrimitive()) {
            if (d.getAsJsonPrimitive().isBoolean())
                return Boolean.toString(d.getAsBoolean());
            return d.getAsString();
        }
        return d.toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? def : el.getAsBoolean();
    }

    /** @return the underlying JsonObject for advanced inspection. */
    public JsonObject raw() { return root; }

    /** Stable map view of the per-command help texts. */
    public Map<String, String> commandHelps() {
        Map<String, String> out = new TreeMap<>();
        for (String n : commandNames()) out.put(n, commandHelp(n));
        return out;
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    /**
     * UI grouping metadata for a collection of {@link ParamSpec}s that share
     * the same {@link ParamSpec#group} key. A group can render either as a
     * sub-dialog (button on the main form that opens a modal) or inline
     * (rows appended to the main form, optionally gated by {@link #visibleWhen}).
     */
    public static final class GroupSpec {
        public enum Render { DIALOG, INLINE }

        public final String key;
        public final String title;
        public final Render render;
        /** Label shown on the button that opens the sub-dialog. Dialog groups only. */
        public final String buttonLabel;
        /** If non-null, the group (button or inline rows) is only shown when this condition holds. */
        public final ParamSpec.VisibleWhen visibleWhen;

        public GroupSpec(String key, String title, Render render,
                         String buttonLabel, ParamSpec.VisibleWhen vw) {
            this.key = key;
            this.title = title == null ? key : title;
            this.render = render == null ? Render.DIALOG : render;
            this.buttonLabel = buttonLabel;
            this.visibleWhen = vw;
        }
    }

    /**
     * @return group key → {@link GroupSpec}, in schema declaration order.
     *         Empty when the command declares no {@code groups} object.
     */
    public Map<String, GroupSpec> groupsFor(String command) {
        JsonObject cmd = command(command);
        if (cmd == null || !cmd.has("groups") || cmd.get("groups").isJsonNull())
            return Map.of();
        JsonObject groups = cmd.getAsJsonObject("groups");
        Map<String, GroupSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : groups.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject g = e.getValue().getAsJsonObject();
            String renderStr = str(g, "render");
            GroupSpec.Render render = "inline".equalsIgnoreCase(renderStr)
                    ? GroupSpec.Render.INLINE : GroupSpec.Render.DIALOG;
            out.put(e.getKey(), new GroupSpec(
                    e.getKey(),
                    str(g, "title"),
                    render,
                    str(g, "button_label"),
                    parseVisibleWhen(g.get("visible_when"))));
        }
        return out;
    }

    private static ParamSpec.VisibleWhen parseVisibleWhen(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String flag = str(o, "flag");
        if (flag == null) return null;
        String equals = null;
        Boolean isSet = null;
        JsonElement eq = o.get("equals");
        if (eq != null && !eq.isJsonNull()) {
            if (eq.isJsonPrimitive() && eq.getAsJsonPrimitive().isBoolean())
                equals = Boolean.toString(eq.getAsBoolean());
            else
                equals = eq.getAsString();
        }
        JsonElement is = o.get("is_set");
        if (is != null && !is.isJsonNull())
            isSet = is.getAsBoolean();
        return new ParamSpec.VisibleWhen(flag, equals, isSet);
    }
}
