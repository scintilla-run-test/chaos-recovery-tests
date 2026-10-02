package dev.oreslang.compiler;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable compiler policy for the Oreslang front end.
 *
 * <p>The two implicit-type switches are tri-state overrides. When an override
 * is absent, {@code strict} supplies its value. This deliberately matches the
 * useful part of TypeScript's strict umbrella: callers can enable strict mode
 * and still override one check explicitly while migrating existing code.</p>
 */
public record CompilerOptions(
        boolean strict,
        Boolean noImplicitAny,
        Boolean noImplicitUnknown) {

    public static final String ENV_STRICT = "ORES_COMPILER_STRICT";
    public static final String ENV_NO_IMPLICIT_ANY = "ORES_NO_IMPLICIT_ANY";
    public static final String ENV_NO_IMPLICIT_UNKNOWN = "ORES_NO_IMPLICIT_UNKNOWN";

    // flags-2-env uses the positive bool name so --no-implicit-any and
    // --no-implicit-unknown are natural boolean negations.
    public static final String ENV_IMPLICIT_ANY = "ORES_IMPLICIT_ANY";
    public static final String ENV_IMPLICIT_UNKNOWN = "ORES_IMPLICIT_UNKNOWN";

    public static CompilerOptions defaults() {
        return new CompilerOptions(false, null, null);
    }

    public static CompilerOptions strictDefaults() {
        return new CompilerOptions(true, null, null);
    }

    public boolean noImplicitAnyEnabled() {
        return noImplicitAny != null ? noImplicitAny : strict;
    }

    public boolean noImplicitUnknownEnabled() {
        return noImplicitUnknown != null ? noImplicitUnknown : strict;
    }

    /**
     * Resolves the compiler options from the environment-shaped map emitted by
     * flags-2-env. Direct ORES_NO_IMPLICIT_* values win over the positive
     * ORES_IMPLICIT_* compatibility channel when both are present.
     */
    public static CompilerOptions fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");

        boolean strict = optionalBoolean(environment, ENV_STRICT) != null
                && optionalBoolean(environment, ENV_STRICT);

        Boolean noImplicitAny = optionalBoolean(environment, ENV_NO_IMPLICIT_ANY);
        if (noImplicitAny == null) {
            Boolean allowImplicitAny = optionalBoolean(environment, ENV_IMPLICIT_ANY);
            if (allowImplicitAny != null) noImplicitAny = !allowImplicitAny;
        }

        Boolean noImplicitUnknown = optionalBoolean(environment, ENV_NO_IMPLICIT_UNKNOWN);
        if (noImplicitUnknown == null) {
            Boolean allowImplicitUnknown = optionalBoolean(environment, ENV_IMPLICIT_UNKNOWN);
            if (allowImplicitUnknown != null) noImplicitUnknown = !allowImplicitUnknown;
        }

        return new CompilerOptions(strict, noImplicitAny, noImplicitUnknown);
    }

    private static Boolean optionalBoolean(Map<String, String> environment, String key) {
        String raw = environment.get(key);
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                    "invalid boolean compiler option " + key + "='" + raw + "'");
        };
    }
}
