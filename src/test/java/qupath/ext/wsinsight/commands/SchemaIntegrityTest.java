package qupath.ext.wsinsight.commands;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sanity checks on the bundled wsinsight-cli-schema.json.
 */
public class SchemaIntegrityTest {

    @Test
    void schemaLoaderAcceptsBundled() throws IOException {
        SchemaLoader loader = SchemaLoader.fromBundled();
        assertNotNull(loader);
        assertTrue(!loader.commandNames().isEmpty(),
                "Bundled schema must expose at least one command");
    }

    @Test
    void allGroupReferencesResolve() throws IOException {
        SchemaLoader loader = SchemaLoader.fromBundled();
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
    void allVisibleWhenFlagsExist() throws IOException {
        SchemaLoader loader = SchemaLoader.fromBundled();
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
