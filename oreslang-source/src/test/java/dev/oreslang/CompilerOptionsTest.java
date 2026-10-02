package dev.oreslang;

import dev.oreslang.compiler.CompilerOptions;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CompilerOptionsTest {
    @Test
    void strictEnablesBothImplicitDynamicTypeGates() {
        CompilerOptions options = CompilerOptions.fromEnvironment(Map.of(
                CompilerOptions.ENV_STRICT, "true"));

        assertTrue(options.noImplicitAnyEnabled());
        assertTrue(options.noImplicitUnknownEnabled());
    }

    @Test
    void individualOverridesWinOverStrict() {
        CompilerOptions options = CompilerOptions.fromEnvironment(Map.of(
                CompilerOptions.ENV_STRICT, "true",
                CompilerOptions.ENV_NO_IMPLICIT_ANY, "false"));

        assertFalse(options.noImplicitAnyEnabled());
        assertTrue(options.noImplicitUnknownEnabled());
    }

    @Test
    void noImplicitAnyRejectsOnlyCompilerCreatedAny() {
        String inferred = """
                define module app
                  fnc choose(bool flag) {
                    if flag do
                      return 1;
                    else
                      return 2;
                    fi
                  }
                end
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(inferred, CompilerOptions.defaults()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        inferred,
                        new CompilerOptions(false, true, false)));
        assertTrue(error.getMessage().contains("implicit any"));

        String explicit = """
                define module app
                  fnc identity(any value) => any {
                    return value;
                  }
                end
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                explicit,
                new CompilerOptions(false, true, false)));
    }

    @Test
    void noImplicitUnknownRejectsEscapingUnknownButAllowsExplicitUnknown() {
        String inferred = """
                define module app
                  fnc example() => void {
                    val value = None;
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        inferred,
                        new CompilerOptions(false, false, true)));
        assertTrue(error.getMessage().contains("implicit unknown"));

        String explicit = """
                define module app
                  fnc identity(unknown value) => unknown {
                    return value;
                  }
                end
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                explicit,
                new CompilerOptions(false, false, true)));
    }

    @Test
    void flags2envPositiveCompatibilityVariablesMapToNoImplicitChecks() {
        CompilerOptions options = CompilerOptions.fromEnvironment(Map.of(
                CompilerOptions.ENV_IMPLICIT_ANY, "false",
                CompilerOptions.ENV_IMPLICIT_UNKNOWN, "true"));

        assertTrue(options.noImplicitAnyEnabled());
        assertFalse(options.noImplicitUnknownEnabled());
    }
}
