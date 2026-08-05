package io.github.subnocte.springwiring.index;

import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.scanner.SourceScanner;
import io.github.subnocte.springwiring.tx.TransactionalIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holder for the endpoint and bean indexes that keeps them consistent with the sources
 * on disk: every access checks a cheap fingerprint of the scanned files (path, mtime,
 * size) and rebuilds both indexes before answering when anything changed. Tool results
 * are therefore always based on the code as it is at call time — a stale index would be
 * silently wrong, which this project's contract forbids.
 */
public final class CodeIndexes {

    /** The indexes built from one consistent view of the sources. */
    public record Snapshot(EndpointIndex endpointIndex, BeanIndex beanIndex,
                           TransactionalIndex transactionalIndex) {
    }

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
    private Map<Path, FileStamp> fingerprint;
    private Snapshot snapshot;

    private CodeIndexes(Path root, Map<Path, FileStamp> fingerprint, Snapshot snapshot) {
        this.root = root;
        this.fingerprint = fingerprint;
        this.snapshot = snapshot;
    }

    /** Builds the initial indexes eagerly, like the previous startup behavior. */
    public static CodeIndexes forRoot(Path root) {
        List<Path> files = SourceScanner.scan(root);
        return new CodeIndexes(root, fingerprintOf(files), build(files));
    }

    /**
     * The current snapshot, rebuilt first if any scanned file was added, removed, or
     * modified since the last build. Unchanged sources return the same snapshot instance;
     * the staleness check itself is a directory walk plus one stat per file.
     */
    public synchronized Snapshot current() {
        List<Path> files = SourceScanner.scan(root);
        Map<Path, FileStamp> fresh = fingerprintOf(files);
        if (!fresh.equals(fingerprint)) {
            snapshot = build(files);
            fingerprint = fresh;
        }
        return snapshot;
    }

    private static Snapshot build(List<Path> files) {
        return new Snapshot(EndpointIndex.build(files), BeanIndex.build(files),
                TransactionalIndex.build(files));
    }

    private static Map<Path, FileStamp> fingerprintOf(List<Path> files) {
        Map<Path, FileStamp> stamps = new HashMap<>();
        for (Path file : files) {
            stamps.put(file, FileStamp.of(file));
        }
        return stamps;
    }
}
