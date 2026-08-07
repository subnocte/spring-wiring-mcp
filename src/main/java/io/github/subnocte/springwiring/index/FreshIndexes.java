package io.github.subnocte.springwiring.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Generic holder that keeps a built snapshot in sync with the files on disk: every access
 * checks a cheap fingerprint of a caller-supplied file scan (path, mtime, size) and
 * rebuilds the snapshot before answering when anything changed. Tool results built on top
 * of this are therefore always based on the code as it is at call time — a stale index
 * would be silently wrong, which this project's contract forbids.
 *
 * <p>This is the mechanism shared by every "rebuild when the sources change" index in the
 * project: {@link CodeIndexes} is the production-source specialization (scans via
 * {@link io.github.subnocte.springwiring.scanner.SourceScanner#scan}); a lazily-built
 * test-source specialization scans via
 * {@link io.github.subnocte.springwiring.scanner.SourceScanner#scanTestSources}. Both wrap
 * an instance of this class instead of duplicating the fingerprint/rebuild logic.
 *
 * @param <T> the built snapshot type (e.g. {@link CodeIndexes.Snapshot})
 */
public final class FreshIndexes<T> {

    /** What "unchanged" means per file: same size and same modification time. */
    private record FileStamp(long size, long modifiedMillis) {
        static FileStamp of(Path file) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to stat source file: " + file, e);
            }
        }
    }

    private final Path root;
    private final Function<Path, List<Path>> scanner;
    private final Function<List<Path>, T> builder;
    private Map<Path, FileStamp> fingerprint;
    private T snapshot;

    private FreshIndexes(Path root, Function<Path, List<Path>> scanner, Function<List<Path>, T> builder,
                          Map<Path, FileStamp> fingerprint, T snapshot) {
        this.root = root;
        this.scanner = scanner;
        this.builder = builder;
        this.fingerprint = fingerprint;
        this.snapshot = snapshot;
    }

    /**
     * Builds the initial snapshot eagerly by scanning {@code root} with {@code scanner} and
     * building it with {@code builder}. Both functions are retained so later staleness
     * checks and rebuilds ({@link #current()}) reuse exactly the same scan/build logic.
     */
    public static <T> FreshIndexes<T> forRoot(
            Path root, Function<Path, List<Path>> scanner, Function<List<Path>, T> builder) {
        List<Path> files = scanner.apply(root);
        return new FreshIndexes<>(root, scanner, builder, fingerprintOf(files), builder.apply(files));
    }

    /** The directory this instance was built from. */
    public Path root() {
        return root;
    }

    /**
     * The current snapshot, rebuilt first if any scanned file was added, removed, or
     * modified since the last build. Unchanged sources return the same snapshot instance;
     * the staleness check itself is a directory walk plus one stat per file.
     */
    public synchronized T current() {
        List<Path> files = scanner.apply(root);
        Map<Path, FileStamp> fresh = fingerprintOf(files);
        if (!fresh.equals(fingerprint)) {
            snapshot = builder.apply(files);
            fingerprint = fresh;
        }
        return snapshot;
    }

    private static Map<Path, FileStamp> fingerprintOf(List<Path> files) {
        Map<Path, FileStamp> stamps = new HashMap<>();
        for (Path file : files) {
            stamps.put(file, FileStamp.of(file));
        }
        return stamps;
    }
}
