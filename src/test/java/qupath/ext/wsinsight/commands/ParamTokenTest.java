package qupath.ext.wsinsight.commands;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * click tuple options need one argv token per element, but comma-separated
 * single arguments must survive untouched — these pin both sides.
 */
public class ParamTokenTest {

    private static ParamSpec param(String flag, int nargs, String def) {
        return ParamSpec.builder()
                .flag(flag)
                .label(flag)
                .kind(ParamSpec.Kind.STRING)
                .defaultValue(def)
                .nargs(nargs)
                .build();
    }

    @Test
    void tupleOptionSplitsIntoSeparateTokens() {
        ParamSpec p = param("--seg-thumbsize", 2, "2048 2048");
        assertEquals(List.of("2048", "2048"),
                GenericCommandDialog.tokensFor(p, "2048 2048"));
    }

    @Test
    void bracketedValueSplitsEvenWhenSchemaPredatesNargs() {
        // Schemas generated before `nargs` was recorded render tuple defaults
        // as "[2048,2048]", and that value also lingers in LastUsedValues.
        ParamSpec p = param("--seg-thumbsize", 1, "[2048,2048]");
        assertEquals(List.of("2048", "2048"),
                GenericCommandDialog.tokensFor(p, "[2048,2048]"));
    }

    @Test
    void commaSeparatedSingleArgumentIsNotSplit() {
        // --niche-leiden-res takes ONE string containing commas.
        ParamSpec p = param("--niche-leiden-res", 1, "0.5,1.0,2.0");
        assertEquals(List.of("0.5,1.0,2.0"),
                GenericCommandDialog.tokensFor(p, "0.5,1.0,2.0"));
    }

    @Test
    void plainValuePassesThrough() {
        ParamSpec p = param("--batch-size", 1, "32");
        assertEquals(List.of("32"), GenericCommandDialog.tokensFor(p, "32"));
    }

    @Test
    void pathWithSpacesIsNotSplit() {
        ParamSpec p = param("--some-dir", 1, "");
        assertEquals(List.of("/data/my slides"),
                GenericCommandDialog.tokensFor(p, "/data/my slides"));
    }

    @Test
    void quotedTupleElementsAreUnquoted() {
        ParamSpec p = param("--pair", 2, "");
        assertEquals(List.of("a", "b"), GenericCommandDialog.tokensFor(p, "[\"a\",\"b\"]"));
    }
}
