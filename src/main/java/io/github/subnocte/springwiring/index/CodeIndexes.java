package io.github.subnocte.springwiring.index;

import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;
import io.github.subnocte.springwiring.scanner.SourceScanner;
import io.github.subnocte.springwiring.tx.TransactionalIndex;

import java.nio.file.Path;
import java.util.List;

/**
 * Holder for the endpoint and bean indexes that keeps them consistent with the sources
 * on disk: every access checks a cheap fingerprint of the scanned files (path, mtime,
 * size) and rebuilds both indexes before answering when anything changed. Tool results
 * are therefore always based on the code as it is at call time — a stale index would be
 * silently wrong, which this project's contract forbids.
 *
 * <p>Thin, public-surface-preserving specialization of {@link FreshIndexes} for
 * production sources: scans via {@link SourceScanner#scan} and builds via {@link #build}.
 */
public final class CodeIndexes {

    /** The indexes built from one consistent view of the sources. */
    public record Snapshot(EndpointIndex endpointIndex, BeanIndex beanIndex,
                           TransactionalIndex transactionalIndex) {
    }

    private final FreshIndexes<Snapshot> delegate;

    private CodeIndexes(FreshIndexes<Snapshot> delegate) {
        this.delegate = delegate;
    }

    /** Builds the initial indexes eagerly, like the previous startup behavior. */
    public static CodeIndexes forRoot(Path root) {
        return new CodeIndexes(FreshIndexes.forRoot(root, SourceScanner::scan, CodeIndexes::build));
    }

    /** The directory this instance indexes. */
    public Path root() {
        return delegate.root();
    }

    /**
     * The current snapshot, rebuilt first if any scanned file was added, removed, or
     * modified since the last build. Unchanged sources return the same snapshot instance;
     * the staleness check itself is a directory walk plus one stat per file.
     */
    public Snapshot current() {
        return delegate.current();
    }

    private static Snapshot build(List<Path> files) {
        return new Snapshot(EndpointIndex.build(files), BeanIndex.build(files),
                TransactionalIndex.build(files));
    }
}
