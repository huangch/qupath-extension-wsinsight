package qupath.ext.wsinsight.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dialog sends every value it displays, so a flag whose CLI default is on
 * must be expressible as off. Omitting it would leave the CLI on its default
 * and make the form disagree with the run.
 */
public class OffFlagTest {

    private static ParamSpec boolSpec(String flag, String offFlag, String def) {
        return ParamSpec.builder()
                .flag(flag).offFlag(offFlag).label(flag)
                .kind(ParamSpec.Kind.BOOL_FLAG).defaultValue(def).build();
    }

    /** Mirrors the BOOL_FLAG branch of the dialog's argv builder. */
    private static List<String> emit(ParamSpec spec, boolean checked) {
        if (checked) return List.of(spec.flag);
        return spec.offFlag != null ? List.of(spec.offFlag) : List.of();
    }

    @Test
    public void defaultOnFlagEmitsNegatedFormWhenCleared() {
        ParamSpec pinMemory = boolSpec("--pin-memory", "--no-pin-memory", "true");

        assertEquals(List.of("--pin-memory"), emit(pinMemory, true));
        assertEquals(List.of("--no-pin-memory"), emit(pinMemory, false),
                "clearing a default-on flag must say so; omitting it leaves the CLI default on");
    }

    @Test
    public void defaultOffFlagIsOmittedWhenCleared() {
        ParamSpec overwrite = boolSpec("--overwrite", null, "false");

        assertEquals(List.of("--overwrite"), emit(overwrite, true));
        assertTrue(emit(overwrite, false).isEmpty(),
                "omission already means off for a flag defaulting to off");
    }

    @Test
    public void offFlagDefaultsToNull() {
        assertNull(ParamSpec.builder().flag("--x").label("x")
                .kind(ParamSpec.Kind.BOOL_FLAG).build().offFlag);
    }

    /**
     * Parsing contract, on a fixture rather than the user's installed schema:
     * the negated form is picked up from the flag pair, and a lone flag that
     * merely starts with {@code --no-} is not mistaken for one.
     */
    @Test
    public void offFlagIsReadFromTheSchemaFlagPair(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cli-schema.json");
        Files.writeString(file, """
                {
                  "schema_version": 1,
                  "commands": {
                    "infer": {
                      "help": "",
                      "params": [
                        {"name": "pin_memory", "param_type": "option", "kind": "bool",
                         "is_flag": true, "default": true,
                         "flags": ["--pin-memory", "--no-pin-memory"]},
                        {"name": "overwrite", "param_type": "option", "kind": "bool",
                         "is_flag": true, "default": false, "flags": ["--overwrite"]},
                        {"name": "no_neighborhood", "param_type": "option", "kind": "bool",
                         "is_flag": true, "default": false, "flags": ["--no-neighborhood"]}
                      ]
                    }
                  }
                }
                """);

        Map<String, ParamSpec> byFlag = new HashMap<>();
        for (ParamSpec s : SchemaLoader.fromFile(file).specsFor("infer")) byFlag.put(s.flag, s);

        assertEquals("--no-pin-memory", byFlag.get("--pin-memory").offFlag);
        assertEquals("true", byFlag.get("--pin-memory").defaultValue);
        assertNull(byFlag.get("--overwrite").offFlag);
        assertNull(byFlag.get("--no-neighborhood").offFlag,
                "a lone --no-x flag is its own primary form, not the off half of a pair");
    }
}
