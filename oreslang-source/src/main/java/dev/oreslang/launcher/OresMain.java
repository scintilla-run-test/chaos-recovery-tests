package dev.oreslang.launcher;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.CompilerOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OresMain {
    private OresMain() { }

    public static void main(String[] args) throws Exception {
        boolean strictIsolate = false;
        String mode = "jit";
        String platform = "server";
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        Map<String, String> compilerEnvironment = new HashMap<>(System.getenv());
        String filename = null;

        for (String arg : args) {
            if (arg.equals("--strict-isolate")) strictIsolate = true;
            else if (arg.equals("--strict") || arg.equals("--strict-compiler")) {
                compilerEnvironment.put(CompilerOptions.ENV_STRICT, "true");
            } else if (arg.equals("--no-strict") || arg.equals("--no-strict-compiler")) {
                compilerEnvironment.put(CompilerOptions.ENV_STRICT, "false");
            } else if (arg.equals("--no-implicit-any") || arg.equals("--noImplicitAny")) {
                compilerEnvironment.put(CompilerOptions.ENV_NO_IMPLICIT_ANY, "true");
            } else if (arg.equals("--implicit-any") || arg.equals("--allow-implicit-any")) {
                compilerEnvironment.put(CompilerOptions.ENV_NO_IMPLICIT_ANY, "false");
            } else if (arg.equals("--no-implicit-unknown") || arg.equals("--noImplicitUnknown")) {
                compilerEnvironment.put(CompilerOptions.ENV_NO_IMPLICIT_UNKNOWN, "true");
            } else if (arg.equals("--implicit-unknown") || arg.equals("--allow-implicit-unknown")) {
                compilerEnvironment.put(CompilerOptions.ENV_NO_IMPLICIT_UNKNOWN, "false");
            } else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
            else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
            else if (arg.startsWith("--allow=")) {
                String raw = arg.substring("--allow=".length());
                if (!raw.isBlank()) {
                    for (String value : raw.split(",")) {
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
                    }
                }
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else if (filename == null) filename = arg;
            else throw new IllegalArgumentException("only one .ores file may be supplied");
        }

        if (filename == null) {
            System.err.println("usage: ores [--strict|--no-strict] [--no-implicit-any|--implicit-any] [--no-implicit-unknown|--implicit-unknown] [--strict-isolate] [--mode=aot|jit|hybrid] [--platform=server|windows|macos|linux|android|ios] [--allow=CAP,...] <file.ores>");
            System.exit(2);
            return;
        }

        Path path = Path.of(filename);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        CompilerOptions compilerOptions = CompilerOptions.fromEnvironment(compilerEnvironment);
        OresCompiler.parseAndTypeCheck(Files.readString(path), compilerOptions);

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        IsolatePolicy policy = strictIsolate ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        Context.Builder builder = policy.restrictedContextBuilder(profile);
        Source source = Source.newBuilder(OresLanguage.ID, new File(filename))
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = builder.build()) {
            context.eval(source);
        }
    }
}
