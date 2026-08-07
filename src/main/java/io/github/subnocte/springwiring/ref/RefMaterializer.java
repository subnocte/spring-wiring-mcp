package io.github.subnocte.springwiring.ref;

import io.github.subnocte.springwiring.scanner.SourceScanner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The only place in this project that knows git. Resolves a ref to an immutable commit SHA
 * in a caller-supplied repository, then materializes that SHA's analyzable sources (as
 * {@link SourceScanner#isSourceFile(Path)} defines "analyzable") onto local disk under
 * {@code <refsBaseDir>/<sha>/} so {@link io.github.subnocte.springwiring.index.CodeIndexesRegistry}
 * can index it like any other directory.
 *
 * <p>A SHA is immutable, so once materialized a directory is reused forever (until LRU
 * eviction); the ref name itself is never cached as a key — every call re-resolves it with
 * {@code rev-parse}, so a moving branch ref (e.g. after a {@code fetch}) is picked up on the
 * next call. All failures (unresolvable ref, missing git executable, a repository that
 * isn't one, a failed archive) are reported as a {@link Failure} with the reason, never
 * thrown.
 *
 * <p>Implementation notes: git is invoked via {@link ProcessBuilder} directly, never
 * through a shell, so ref names are never subject to shell interpretation. The archive
 * itself is streamed straight from {@code git archive}'s stdout through the JDK's own
 * {@link ZipInputStream} (no archiving library dependency), extracted into a staging
 * directory, then promoted into place with an atomic rename so a reader never observes a
 * partially-extracted directory and two concurrent materializations of the same SHA cannot
 * corrupt each other.
 */
public final class RefMaterializer {

    /** Default cap on simultaneously materialized ref generations; overridable via constructor. */
    public static final int DEFAULT_MAX_GENERATIONS = 5;

    private static final String DEFAULT_GIT_COMMAND = "git";
    private static final String STAGING_DIR_INFIX = "-tmp-";

    /** Outcome of {@link #materialize(Path, String)}. */
    public sealed interface Result permits Success, Failure {
    }

    /**
     * @param directory the local directory holding {@code sha}'s analyzable sources
     * @param sha the commit SHA the requested ref resolved to
     * @param committedAt the commit's author-independent commit timestamp, ISO-8601 ({@code %cI})
     */
    public record Success(Path directory, String sha, String committedAt) implements Result {
    }

    /** @param reason human-readable explanation of why materialization failed */
    public record Failure(String reason) implements Result {
    }

    /** Thrown internally between the resolution steps; always converted to a {@link Failure}. */
    private static final class MaterializeFailure extends RuntimeException {
        MaterializeFailure(String message) {
            super(message);
        }
    }

    private final Path refsBaseDir;
    private final int maxGenerations;
    private final String gitCommand;

    public RefMaterializer(Path refsBaseDir) {
        this(refsBaseDir, DEFAULT_MAX_GENERATIONS);
    }

    public RefMaterializer(Path refsBaseDir, int maxGenerations) {
        this(refsBaseDir, maxGenerations, DEFAULT_GIT_COMMAND);
    }

    /** Package-private seam for tests: lets a test simulate a missing git executable. */
    RefMaterializer(Path refsBaseDir, int maxGenerations, String gitCommand) {
        this.refsBaseDir = refsBaseDir;
        this.maxGenerations = maxGenerations;
        this.gitCommand = gitCommand;
    }

    /** The directory materialized ref generations are stored under. */
    public Path refsBaseDirectory() {
        return refsBaseDir;
    }

    /**
     * Resolves {@code ref} to a commit SHA in the repository at {@code repoRoot} and
     * returns a local directory holding that commit's analyzable sources, extracting it
     * first if this is the first request for that SHA.
     */
    public synchronized Result materialize(Path repoRoot, String ref) {
        try {
            String sha = resolveSha(repoRoot, ref);
            String committedAt = resolveCommittedAt(repoRoot, sha);
            Path dest = refsBaseDir.resolve(sha);
            if (!Files.isDirectory(dest)) {
                materializeInto(repoRoot, sha, dest);
            }
            touch(dest);
            enforceMaxGenerations();
            return new Success(dest, sha, committedAt);
        } catch (MaterializeFailure failure) {
            return new Failure(failure.getMessage());
        }
    }

    private String resolveSha(Path repoRoot, String ref) {
        GitOutput out = runGit(repoRoot, "rev-parse", "--verify", ref + "^{commit}");
        if (!out.ok()) {
            throw new MaterializeFailure(
                    "Failed to resolve ref '" + ref + "' in " + repoRoot + ": " + out.stderr().trim());
        }
        return out.stdout().trim();
    }

    private String resolveCommittedAt(Path repoRoot, String sha) {
        GitOutput out = runGit(repoRoot, "show", "-s", "--format=%cI", sha);
        if (!out.ok()) {
            throw new MaterializeFailure(
                    "Failed to read commit timestamp for " + sha + " in " + repoRoot + ": " + out.stderr().trim());
        }
        return out.stdout().trim();
    }

    private void materializeInto(Path repoRoot, String sha, Path dest) {
        try {
            Files.createDirectories(refsBaseDir);
        } catch (IOException e) {
            throw new MaterializeFailure("Failed to create refs directory " + refsBaseDir + ": " + e.getMessage());
        }
        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory(refsBaseDir, sha + STAGING_DIR_INFIX);
        } catch (IOException e) {
            throw new MaterializeFailure(
                    "Failed to create staging directory under " + refsBaseDir + ": " + e.getMessage());
        }
        try {
            extractArchive(repoRoot, sha, stagingDir);
            try {
                Files.move(stagingDir, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException raced) {
                // dest now exists: another materialize() (possibly in another process) won
                // the race for this immutable, content-addressed SHA. Its directory is
                // equally valid; fall through and let the finally block discard ours.
            } catch (IOException e) {
                throw new MaterializeFailure("Failed to finalize materialized ref " + sha + ": " + e.getMessage());
            }
        } finally {
            deleteRecursivelyQuietly(stagingDir);
        }
    }

    private void extractArchive(Path repoRoot, String sha, Path destDir) {
        List<String> command = List.of(gitCommand, "-C", repoRoot.toString(), "archive", "--format=zip", sha);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new MaterializeFailure("Failed to run '" + gitCommand + "': " + e.getMessage());
        }

        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain = new Thread(() -> {
            try (BufferedReader reader = process.errorReader()) {
                reader.lines().forEach(line -> stderr.append(line).append('\n'));
            } catch (IOException ignored) {
                // best-effort diagnostics only; a real failure still surfaces via exit code
            }
        }, "spring-wiring-ref-archive-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        try (ZipInputStream zis = new ZipInputStream(process.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    extractEntryIfAnalyzable(zis, entry, destDir);
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new MaterializeFailure("git archive failed for " + sha + " in " + repoRoot + ": " + e.getMessage());
        }

        int exit;
        try {
            exit = process.waitFor();
            stderrDrain.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MaterializeFailure("Interrupted while running git archive for " + sha);
        }
        if (exit != 0) {
            throw new MaterializeFailure(
                    "git archive failed for " + sha + " in " + repoRoot + ": " + stderr.toString().trim());
        }
    }

    private static void extractEntryIfAnalyzable(ZipInputStream zis, ZipEntry entry, Path destDir) throws IOException {
        // git archive entry names always use '/' regardless of OS; Path.of splits on the
        // platform separator, which is '/' on every platform this server targets.
        Path relative = Path.of(entry.getName());
        if (!SourceScanner.isSourceFile(relative)) {
            return;
        }
        Path target = destDir.resolve(relative).normalize();
        if (!target.startsWith(destDir)) {
            throw new IOException("Zip entry escapes destination directory: " + entry.getName());
        }
        Files.createDirectories(target.getParent());
        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void touch(Path dest) {
        try {
            Files.setLastModifiedTime(dest, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // best-effort LRU recency marker only; does not affect this call's correctness
        }
    }

    private void enforceMaxGenerations() {
        List<Path> generations;
        try (var stream = Files.list(refsBaseDir)) {
            generations = stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().contains(STAGING_DIR_INFIX))
                    .sorted(Comparator.comparingLong(RefMaterializer::lastModifiedMillisQuiet))
                    .toList();
        } catch (IOException e) {
            return; // best-effort cleanup only; must never fail materialization
        }
        int excess = generations.size() - maxGenerations;
        for (int i = 0; i < excess; i++) {
            deleteRecursivelyQuietly(generations.get(i));
        }
    }

    private static long lastModifiedMillisQuiet(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup only
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    private GitOutput runGit(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitCommand);
        command.add("-C");
        command.add(repoRoot.toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new GitOutput(exit, stdout, stderr);
        } catch (IOException e) {
            throw new MaterializeFailure("Failed to run '" + gitCommand + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MaterializeFailure("Interrupted while running '" + gitCommand + "'");
        }
    }

    private record GitOutput(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }
    }
}
