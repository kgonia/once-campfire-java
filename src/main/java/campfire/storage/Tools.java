package campfire.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// The media command-line tools Active Storage shells out to (or that stand in for ruby-vips),
/// found on `PATH` (`ActiveStorage.paths` is empty in Campfire). Every run has a timeout, closed
/// stdin and drained pipes, and never goes through a shell.
public enum Tools {
    /// libvips' CLI (`libvips-tools`).
    VIPS("vips", "--version"),
    VIPSHEADER("vipsheader", "--version"),
    FFMPEG("ffmpeg", "-version"),
    FFPROBE("ffprobe", "-version");

    /// `Vips.block_untrusted(true)` from config/initializers/vips.rb, for runs that load uploads.
    static final Map<String, String> BLOCK_UNTRUSTED = Map.of("VIPS_BLOCK_UNTRUSTED", "1");
    /// Bytes of stdout/stderr kept in memory.
    private static final int CAPTURE_LIMIT = 4 * 1024 * 1024;

    private final String command;
    private final String versionFlag;
    private volatile Boolean available;

    Tools(String command, String versionFlag) {
        this.command = command;
        this.versionFlag = versionFlag;
    }

    public String command() {
        return command;
    }

    /// Whether `<tool> -version` runs and succeeds (memoized, like `VideoPreviewer.ffmpeg_exists?`).
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            try {
                known = run(List.of(versionFlag), Duration.ofSeconds(10), null, Map.of()).exitCode() == 0;
            } catch (StorageException e) {
                known = false;
            }
            available = known;
        }
        return known;
    }

    /// For tests: forget what {@link #available} found.
    static void resetAvailability() {
        for (Tools tool : values()) tool.available = null;
    }

    public record Result(int exitCode, byte[] stdout, String stderr) {
        public String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    /// Runs the tool with `args`. Stdout goes to `stdoutFile` when given, else is captured. Throws
    /// {@link StorageException.Unavailable} when the tool isn't installed and
    /// {@link StorageException} when it overruns `timeout` (it's killed).
    public Result run(List<String> args, Duration timeout, Path stdoutFile, Map<String, String> environment) {
        var commandLine = new java.util.ArrayList<String>(args.size() + 1);
        commandLine.add(command);
        commandLine.addAll(args);
        var builder = new ProcessBuilder(commandLine);
        builder.environment().putAll(environment);
        if (stdoutFile != null) builder.redirectOutput(stdoutFile.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new StorageException.Unavailable(command + " is not installed: " + e.getMessage());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        Thread outDrain = stdoutFile == null ? drain(process.getInputStream(), stdout) : null;
        Thread errDrain = drain(process.getErrorStream(), stderr);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new StorageException(command + " timed out after " + timeout.toSeconds() + "s");
            }
            if (outDrain != null) outDrain.join(timeout.toMillis());
            errDrain.join(timeout.toMillis());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new StorageException(command + " interrupted", e);
        }
        String errors;
        synchronized (stderr) {
            errors = stderr.toString(StandardCharsets.UTF_8).strip();
        }
        byte[] out;
        synchronized (stdout) {
            out = stdout.toByteArray();
        }
        return new Result(process.exitValue(), out, errors);
    }

    /// {@link #run}, throwing {@link StorageException} with the tool's stderr when it fails.
    public Result runOrFail(List<String> args, Duration timeout, Path stdoutFile, Map<String, String> environment) {
        Result result = run(args, timeout, stdoutFile, environment);
        if (result.exitCode() != 0) {
            throw new StorageException(command + " failed (status " + result.exitCode() + "): " + result.stderr());
        }
        return result;
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink) {
        return Thread.ofVirtual().name("campfire-media-drain").start(() -> {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (sink) {
                        if (sink.size() < CAPTURE_LIMIT) sink.write(buffer, 0, Math.min(n, CAPTURE_LIMIT - sink.size()));
                    }
                }
            } catch (IOException e) {
                // The process went away; what was read is kept.
            }
        });
    }
}
