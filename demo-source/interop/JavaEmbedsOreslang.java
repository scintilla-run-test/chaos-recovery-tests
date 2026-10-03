import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class JavaEmbedsOreslang {
    private JavaEmbedsOreslang() { }

    public static void main(String[] args) throws Exception {
        String oreslang = """
                define module embedded_math
                  pub fnc twice(int value) => int {
                    return value * 2;
                  }
                end

                pub routine main() => void {
                  stdio.println(embedded_math.twice(21));
                  return;
                }
                """;

        ByteArrayOutputStream guestOutput = new ByteArrayOutputStream();

        Source source = Source.newBuilder("ores", oreslang, "EmbeddedFromJava.ores")
                .mimeType("application/x-oreslang")
                .build();

        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(false)
                .out(guestOutput)
                .build()) {
            context.eval(source);
        }

        String value = guestOutput.toString(StandardCharsets.UTF_8).trim();
        if (!"42".equals(value)) {
            throw new IllegalStateException("expected Oreslang to produce 42, got: " + value);
        }

        System.out.println("java host <- oreslang guest: " + value);
    }
}
