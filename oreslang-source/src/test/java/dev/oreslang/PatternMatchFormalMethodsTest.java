package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PatternMatchFormalMethodsTest {
    @Test
    void lexerRecognizesForwardPipeMatchAndFormalKeywords() {
        var tokens = new Lexer("""
                match x { true => 1, false => 0 }
                x |> f();
                requires true;
                ensures true;
                invariant true;
                decreases 1;
                assert true;
                assume true;
                old(x);
                """).scan();

        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.PIPE_FORWARD));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.REQUIRES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ENSURES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.INVARIANT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.DECREASES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ASSERT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ASSUME));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.OLD));
    }

    @Test
    void exhaustiveBoolMatchInfersReturnType() {
        Ast.Program program = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc choose(bool flag) {
                    return match flag {
                      true => 1,
                      false => 2
                    };
                  }
                end
                """)));

        Ast.FunctionDecl choose = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("$infer$", choose.returnType().name());
    }

    @Test
    void inferredMatchReturnTypeRemainsPreciseAtCallSites() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc choose(bool flag) {
                            return match flag {
                              true => 1,
                              false => 2
                            };
                          }

                          fnc wrong(bool flag) => String {
                            return choose(flag);
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("return value"));
        assertTrue(error.getMessage().contains("expected STRING"));
    }

    @Test
    void inferredMethodMatchReturnTypeRemainsPreciseAtCallSites() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          define class Switch
                            choose(bool flag) {
                              return match flag {
                                true => 1,
                                false => 2
                              };
                            }
                          end

                          fnc wrong(Switch value, bool flag) => String {
                            return value.choose(flag);
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("return value"));
        assertTrue(error.getMessage().contains("expected STRING"));
    }

    @Test
    void rejectsNonExhaustiveBoolMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc choose(bool flag) => int {
                            return match flag {
                              true => 1
                            };
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("non-exhaustive match on bool"));
    }

    @Test
    void guardedArmDoesNotCountTowardExhaustiveness() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc choose(bool flag) => int {
                            return match flag {
                              true if flag => 1,
                              false => 0
                            };
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("non-exhaustive match on bool"));
    }

    @Test
    void optionMatchBindsConstructorPayloadAndIsExhaustive() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc unwrap(Option<int> value) => int {
                    return match value {
                      Some(v) => v,
                      None => 0
                    };
                  }
                end
                """)));
    }

    @Test
    void constructorPayloadMustActuallyCoverConstructorDomain() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc unwrap(Option<int> value) => int {
                            return match value {
                              Some(0) => 0,
                              None => 0
                            };
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("non-exhaustive match on Option"));
    }

    @Test
    void explicitReturnTypeIsCheckedAgainstEveryMatchArm() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc choose(bool flag) => int {
                            return match flag {
                              true => 1,
                              false => "wrong"
                            };
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("match arm"));
        assertTrue(error.getMessage().contains("expected INT"));
    }

    @Test
    void catchAllMakesOpenStructuralMatchExhaustiveAndLaterArmsAreRejected() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Person
                    pub val String name;
                  end

                  fnc label(Person value) => int {
                    return match value {
                      obj{name: "special"} => 1,
                      _ => 0
                    };
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc choose(bool flag) => int {
                            return match flag {
                              _ => 1,
                              true => 2
                            };
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("unreachable match arm"));
    }

    @Test
    void pipeOperatorAndPipeIntoMatchExecute() throws Exception {
        String program = """
                define module app
                  fnc inc(int x) => int { return x + 1; }
                  fnc twice(int x) => int { return x * 2; }

                  pub fnc main() => void {
                    val piped = 2 |> inc() |> twice();
                    val matched = true |> match {
                      true => 7,
                      false => 0
                    };
                    stdio.println(piped);
                    stdio.println(matched);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("6"));
        assertTrue(text.contains("7"));
    }

    @Test
    void nativeContractsAndProofStatementsAreTypeChecked() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc identity(int x) => int
                    requires x >= 0;
                    ensures result >= 0;
                    decreases x;
                  {
                    assert x >= 0;
                    assume true;
                    invariant true;
                    return x;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module app
                          fnc impossible() => int
                            ensures false;
                          {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("ensures condition is statically false"));
    }

    @Test
    void anyIsAvailableAsExplicitInferenceEscapeHatch() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc dynamic(bool flag) => any {
                    return match flag {
                      true => 1,
                      false => "mixed"
                    };
                  }
                end
                """)));
    }
}
