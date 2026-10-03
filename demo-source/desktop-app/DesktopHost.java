import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DesktopHost {
    private static final int GUEST_TIMEOUT_SECONDS = 10;
    private static final int MAX_GUEST_OUTPUT_BYTES = 1024 * 1024;
    private static final int MIN_WIDTH = 200;
    private static final int MAX_WIDTH = 4096;
    private static final int MIN_HEIGHT = 120;
    private static final int MAX_HEIGHT = 2160;
    private static final int MAX_TITLE_CHARS = 256;
    private static final int MAX_BODY_CHARS = 256 * 1024;

    private DesktopHost() { }

    public static void main(String[] args) throws Exception {
        boolean manifestOnly = args.length == 2 && "--manifest-only".equals(args[0]);
        if ((!manifestOnly && args.length != 1) || (manifestOnly && args.length != 2)) {
            throw new IllegalArgumentException(
                    "usage: DesktopHost [--manifest-only] <stitched-app.ores>");
        }

        Path source = Path.of(args[manifestOnly ? 1 : 0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("not a regular Oreslang source file: " + source);
        }

        AppManifest app = loadManifest(source);
        if (manifestOnly) {
            System.out.println(app.title());
            System.out.println(app.width());
            System.out.println(app.height());
            System.out.println(app.body());
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea(app.body());
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            JFrame frame = new JFrame(app.title());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new JScrollPane(text));
            frame.setSize(app.width(), app.height());
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }

    private static AppManifest loadManifest(Path source) throws Exception {
        GuestOutput guest = runGuest(source);
        if (guest.exitCode() != 0) {
            throw new IllegalStateException(
                    "Oreslang guest failed with exit code " + guest.exitCode() + ": " + guest.stderr());
        }

        List<String> lines = guest.stdout().lines().toList();
        if (lines.size() != 4) {
            throw new IllegalStateException(
                    "expected exactly four Oreslang manifest lines, got " + lines.size());
        }

        String title = lines.get(0);
        int width = parseBoundedInt("width", lines.get(1), MIN_WIDTH, MAX_WIDTH);
        int height = parseBoundedInt("height", lines.get(2), MIN_HEIGHT, MAX_HEIGHT);
        String body = lines.get(3);

        if (title.isBlank() || title.length() > MAX_TITLE_CHARS || containsControl(title)) {
            throw new IllegalStateException("invalid desktop title");
        }
        if (body.length() > MAX_BODY_CHARS || body.indexOf('\0') >= 0) {
            throw new IllegalStateException("invalid desktop body");
        }

        return new AppManifest(title, width, height, body);
    }

    private static GuestOutput runGuest(Path source) throws Exception {
        String compiler = System.getenv().getOrDefault("ORESLANG_COMPILER", "oreslang-compiler");
        Process process = new ProcessBuilder(
                compiler,
                "--platform=" + oreslangPlatform(),
                source.toString()
        ).start();

        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = io.submit(
                    () -> readUtf8Bounded(process.getInputStream(), MAX_GUEST_OUTPUT_BYTES));
            Future<String> stderr = io.submit(
                    () -> readUtf8Bounded(process.getErrorStream(), MAX_GUEST_OUTPUT_BYTES));

            if (!process.waitFor(GUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                throw new IllegalStateException("Oreslang guest exceeded the demo timeout");
            }

            return new GuestOutput(
                    process.exitValue(),
                    getCaptured(stdout, "stdout"),
                    getCaptured(stderr, "stderr")
            );
        }
    }

    private static String readUtf8Bounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;

        while ((read = input.read(buffer)) != -1) {
            long before = total;
            total += read;
            if (before < maxBytes) {
                int keep = (int) Math.min(read, maxBytes - before);
                kept.write(buffer, 0, keep);
            }
        }

        if (total > maxBytes) {
            throw new IOException("guest output exceeded " + maxBytes + " bytes");
        }
        return kept.toString(StandardCharsets.UTF_8);
    }

    private static String getCaptured(Future<String> future, String streamName) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            throw new IOException("failed to capture guest " + streamName, cause);
        }
    }

    private static int parseBoundedInt(String name, String value, int min, int max) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("invalid " + name + ": " + value, error);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalStateException(
                    name + " must be between " + min + " and " + max + ", got " + parsed);
        }
        return parsed;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
    }

    private static String oreslangPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "macos";
        if (os.contains("win")) return "windows";
        return "linux";
    }

    private record AppManifest(String title, int width, int height, String body) { }
    private record GuestOutput(int exitCode, String stdout, String stderr) { }
}
