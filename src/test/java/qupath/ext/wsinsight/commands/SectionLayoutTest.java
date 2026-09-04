package qupath.ext.wsinsight.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Grouping of the run dialog's optional flags, which is derived from the CLI
 * flag prefixes because {@code wsinsight schema} carries no GUI hints.
 */
class SectionLayoutTest {

    private static ParamSpec opt(String flag) {
        return ParamSpec.builder().flag(flag).label(label(flag))
                .kind(ParamSpec.Kind.DOUBLE).build();
    }

    private static ParamSpec toggle(String flag) {
        return ParamSpec.builder().flag(flag).label(label(flag))
                .kind(ParamSpec.Kind.BOOL_FLAG).build();
    }

    private static String label(String flag) {
        return flag.substring(2);
    }

    @Test
    void twoOptionsAreEnoughToFormASection() {
        // ncomp only has --ncomp-max-neighbor-distance and --ncomp-k.
        List<ParamSpec> specs = List.of(
                opt("--ncomp-max-neighbor-distance"), opt("--ncomp-k"));

        LinkedHashMap<String, List<ParamSpec>> sections =
                GenericCommandDialog.deriveSections(specs);

        assertEquals(Set.of("ncomp"), sections.keySet());
        assertEquals(2, sections.get("ncomp").size());
    }

    @Test
    void aLoneOptionDoesNotFormASection() {
        assertTrue(GenericCommandDialog.deriveSections(List.of(opt("--patch-size-um")))
                .isEmpty());
    }

    @Test
    void sectionTitlesStayLowerCase() {
        assertEquals("ncomp", GenericCommandDialog.sectionTitle("ncomp"));
        assertEquals("hplot", GenericCommandDialog.sectionTitle("hplot"));
    }

    @Test
    void theSubcommandSwitchLeavesTheMainGridForItsSection() {
        List<ParamSpec> main = new ArrayList<>(List.of(
                opt("--batch-size"), toggle("--ncomp"), toggle("--hplot")));

        LinkedHashMap<String, ParamSpec> switches =
                GenericCommandDialog.takeSectionSwitches(main, Set.of("ncomp", "hplot"));

        assertEquals(Set.of("ncomp", "hplot"), switches.keySet());
        assertEquals("--ncomp", switches.get("ncomp").flag);
        // Removed from the main grid so it is not shown twice.
        assertEquals(List.of("--batch-size"), main.stream().map(s -> s.flag).toList());
    }

    @Test
    void aSectionWithoutASwitchIsLeftAlone() {
        List<ParamSpec> main = new ArrayList<>(List.of(opt("--batch-size")));

        assertTrue(GenericCommandDialog.takeSectionSwitches(main, Set.of("seg")).isEmpty());
        assertEquals(1, main.size());
    }

    /** The main grid as it reaches the two columns, in order. */
    private static List<String> ordered(String... flags) {
        List<ParamSpec> specs = new ArrayList<>();
        for (String f : flags)
            specs.add(opt(f));
        GenericCommandDialog.applyMainOrder(specs);
        return specs.stream().map(s -> s.flag).toList();
    }

    @Test
    void stitchWorkersFollowsNumWorkers() {
        assertEquals(
                List.of("--num-workers", "--stitch-workers", "--pin-memory"),
                ordered("--num-workers", "--pin-memory", "--stitch-workers"));
    }

    @Test
    void directoryPickersTrailOverwriteInOrder() {
        assertEquals(
                List.of("--overwrite", "--region-inference-dir", "--histoqc-dir"),
                ordered("--region-inference-dir", "--histoqc-dir", "--overwrite"));
    }

    @Test
    void theRunPanelEndsUpInTheIntendedOrder() {
        List<String> actual = ordered(
                "--model", "--region-inference-dir", "--batch-size", "--num-workers",
                "--pin-memory", "--cache-image-patches", "--histoqc-dir",
                "--spacing-um-px", "--overwrite", "--stitch-workers");

        assertEquals(
                List.of("--model", "--batch-size", "--num-workers", "--stitch-workers",
                        "--pin-memory", "--cache-image-patches", "--spacing-um-px",
                        "--overwrite", "--region-inference-dir", "--histoqc-dir"),
                actual);

        // The columns split down the middle, so this is what each side shows.
        int left = actual.size() / 2;
        assertEquals(
                List.of("--model", "--batch-size", "--num-workers", "--stitch-workers",
                        "--pin-memory"),
                actual.subList(0, left));
        assertEquals(
                List.of("--cache-image-patches", "--spacing-um-px", "--overwrite",
                        "--region-inference-dir", "--histoqc-dir"),
                actual.subList(left, actual.size()));
    }

    @Test
    void rulesNamingAnAbsentFlagAreSkipped() {
        // --overwrite is hidden for some subcommands; the rest must still apply.
        assertEquals(
                List.of("--num-workers", "--stitch-workers", "--histoqc-dir"),
                ordered("--num-workers", "--histoqc-dir", "--stitch-workers"));
    }

    @Test
    void naturalPlusFudgeOpensAtNaturalSize() {
        // 300 + 24 = 324 is below the 360 floor (sparse forms still look like
        // dialogs), so the floor wins on a 1080p screen.
        assertEquals(360.0, GenericCommandDialog.preferredBodyHeight(300, 1080));
        // 600 + 24 = 624 sits between the floor and the screen cap (918).
        assertEquals(624.0, GenericCommandDialog.preferredBodyHeight(600, 1080));
    }

    @Test
    void theOpeningHeightStopsShortOfTheScreen() {
        // 826 + 24 = 850 ties the 85% cap on a 1000-px screen; cap still wins.
        assertEquals(850.0, GenericCommandDialog.preferredBodyHeight(826, 1000));
    }

    @Test
    void smallFormsLiftToTheFloor() {
        // A 100 px natural body is below the 360 floor on any screen.
        assertEquals(360.0, GenericCommandDialog.preferredBodyHeight(100, 1440));
        assertEquals(360.0, GenericCommandDialog.preferredBodyHeight(100, 800));
    }

    @Test
    void autoMeansTheBackendIsNotPassedAtAll() {
        assertEquals(List.of(), GenericCommandDialog.globalArgs("auto"));
        assertEquals(List.of(), GenericCommandDialog.globalArgs(""));
        assertEquals(List.of(), GenericCommandDialog.globalArgs("   "));
        assertEquals(List.of(), GenericCommandDialog.globalArgs(null));
    }

    @Test
    void anExplicitBackendIsPassedAsAGroupOption() {
        // Click needs group options ahead of the subcommand.
        assertEquals(List.of("--backend", "tiffslide"),
                GenericCommandDialog.globalArgs("tiffslide"));
        assertEquals(List.of("--backend", "openslide"),
                GenericCommandDialog.globalArgs("openslide"));
    }

    @Test
    void onlyBooleanSwitchesAreTaken() {
        // --import takes a value, so it is not a section toggle.
        List<ParamSpec> main = new ArrayList<>(List.of(opt("--import")));

        assertTrue(GenericCommandDialog.takeSectionSwitches(main, Set.of("import")).isEmpty());
        assertFalse(main.isEmpty());
    }
}
