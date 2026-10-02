package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    public static Ast.Program parseAndTypeCheck(String source) {
        return parseAndTypeCheck(source, CompilerOptions.defaults());
    }

    public static Ast.Program parseAndTypeCheck(String source, CompilerOptions compilerOptions) {
        return TypeChecker.check(Parser.parse(source), compilerOptions);
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        return validateForIsolate(source, policy, CompilerOptions.defaults());
    }

    public static Ast.Program validateForIsolate(
            String source,
            IsolatePolicy policy,
            CompilerOptions compilerOptions) {
        Ast.Program program = parseAndTypeCheck(source, compilerOptions);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
