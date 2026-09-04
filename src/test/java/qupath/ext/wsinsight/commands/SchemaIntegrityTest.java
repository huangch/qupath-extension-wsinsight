package qupath.ext.wsinsight.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The schema is user-supplied now, so failures must stay actionable rather than
 * surfacing as an empty menu.
 */
public class SchemaIntegrityTest {

    private static final String MINIMAL = """
            {
              "schema_version": 1,
              "wsinsight_version": "0.9.1",
              "commands": {
                "infer": {
                  "name": "infer",
                  "help": "run inference",
                  "params": [
                    {"name": "model", "kind": "string", "required": false,
                     "default": null, "help": "", "multiple": false,
                     "is_flag": false, "param_type": "option",
                     "flags": ["--model", "-m"]}
                  ]
                }
              },
              "models": [
                {"name": "breast-tumor-resnet34.tcga-brca",
                 "description": "Breast tumor", "hf_repo_id": "kaczmarj/x",
                 "hf_revision": "main",
                 "path": "/app/zoo/kaczmarj/x/main"},
                {"name": "no-description", "description": "",
                 "hf_repo_id": "kaczmarj/y", "hf_revision": "main",
                 "path": null}
              ]
            }
            """;

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void loadsCommandsAndVersion(@TempDir Path dir) throws IOException {
        SchemaLoader loader = SchemaLoader.fromFile(write(dir, "s.json", MINIMAL));
        assertNotNull(loader);
        assertTrue(loader.commandNames().contains("infer"));
        assertEquals("0.9.1", loader.wsinsightVersion());
    }

    @Test
    void parsesModelsAndLabels(@TempDir Path dir) throws IOException {
        List<SchemaLoader.ModelSpec> models =
                SchemaLoader.fromFile(write(dir, "s.json", MINIMAL)).models();
        assertEquals(2, models.size());
        // Label is the bare name: descriptions are long enough to widen the dialog.
        assertEquals("breast-tumor-resnet34.tcga-brca", models.get(0).label());
        assertEquals("Breast tumor", models.get(0).description);
        assertEquals("no-description", models.get(1).label());
    }

    @Test
    void localModelPathIsCarriedThrough(@TempDir Path dir) throws IOException {
        List<SchemaLoader.ModelSpec> models =
                SchemaLoader.fromFile(write(dir, "s.json", MINIMAL)).models();
        // Present → the dialog can pass --zoo-model-dir and skip HuggingFace.
        assertEquals("/app/zoo/kaczmarj/x/main", models.get(0).path);
        // Absent → must be null, not the string "null", so the caller falls
        // back to --model rather than passing a bogus directory.
        assertNull(models.get(1).path);
    }

    @Test
    void missingFileExplainsHowToGenerateIt(@TempDir Path dir) {
        Path missing = dir.resolve("absent.json");
        IOException e = assertThrows(SchemaLoader.SchemaUnavailableException.class,
                () -> SchemaLoader.fromFile(missing));
        assertTrue(e.getMessage().contains("wsinsight schema --output"),
                "Message must tell the user how to produce the file: " + e.getMessage());
    }

    @Test
    void malformedJsonIsReportedNotSwallowed(@TempDir Path dir) throws IOException {
        Path bad = write(dir, "bad.json", "{not json");
        assertThrows(SchemaLoader.SchemaUnavailableException.class,
                () -> SchemaLoader.fromFile(bad));
    }

    @Test
    void schemaWithoutCommandsIsRejected(@TempDir Path dir) throws IOException {
        Path bad = write(dir, "empty.json", "{\"schema_version\": 1}");
        assertThrows(SchemaLoader.SchemaUnavailableException.class,
                () -> SchemaLoader.fromFile(bad));
    }

    @Test
    void olderSchemaWithoutModelsStillLoads(@TempDir Path dir) throws IOException {
        String noModels = MINIMAL.replaceAll("(?s),\\s*\"models\".*?\\]", "");
        SchemaLoader loader = SchemaLoader.fromFile(write(dir, "old.json", noModels));
        assertTrue(loader.commandNames().contains("infer"));
        assertTrue(loader.models().isEmpty(), "absent models must degrade, not throw");
    }

    @Test
    void allGroupReferencesResolve(@TempDir Path dir) throws IOException {
        SchemaLoader loader = SchemaLoader.fromFile(write(dir, "s.json", MINIMAL));
        for (String cmd : loader.commandNames()) {
            Map<String, SchemaLoader.GroupSpec> groups = loader.groupsFor(cmd);
            List<ParamSpec> params = loader.specsFor(cmd);
            assertNotNull(groups, cmd);
            assertNotNull(params, cmd);
            for (ParamSpec p : params) {
                if (p.group == null) continue;
                if (!groups.containsKey(p.group))
                    fail("Command '" + cmd + "' param '" + p.label
                            + "' references undeclared group '" + p.group + "'");
            }
        }
    }

    @Test
    void allVisibleWhenFlagsExist(@TempDir Path dir) throws IOException {
        SchemaLoader loader = SchemaLoader.fromFile(write(dir, "s.json", MINIMAL));
        for (String cmd : loader.commandNames()) {
            List<ParamSpec> params = loader.specsFor(cmd);
            Set<String> flags = new HashSet<>();
            for (ParamSpec p : params) {
                if (p.flag != null) flags.add(p.flag);
            }
            Map<String, SchemaLoader.GroupSpec> groups = loader.groupsFor(cmd);
            for (var ge : groups.entrySet()) {
                ParamSpec.VisibleWhen vw = ge.getValue().visibleWhen;
                if (vw == null || vw.flag == null) continue;
                if (!flags.contains(vw.flag))
                    fail("Command '" + cmd + "' group '" + ge.getKey()
                            + "' visible_when references unknown flag '" + vw.flag + "'");
            }
        }
    }
}
