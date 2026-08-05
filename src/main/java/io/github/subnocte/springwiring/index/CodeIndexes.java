package io.github.subnocte.springwiring.index;

import io.github.subnocte.springwiring.bean.BeanIndex;
import io.github.subnocte.springwiring.endpoint.EndpointIndex;

import java.nio.file.Path;

/**
 * Holder for the endpoint and bean indexes that keeps them consistent with the sources
 * on disk: every access checks a cheap fingerprint of the scanned files (path, mtime,
 * size) and rebuilds both indexes before answering when anything changed. Tool results
 * are therefore always based on the code as it is at call time — a stale index would be
 * silently wrong, which this project's contract forbids.
 */
public final class CodeIndexes {

    /** The two indexes built from one consistent view of the sources. */
    public record Snapshot(EndpointIndex endpointIndex, BeanIndex beanIndex) {
    }

    private final Path root;
    private Snapshot snapshot;

    private CodeIndexes(Path root, Snapshot snapshot) {
        this.root = root;
        this.snapshot = snapshot;
    }

    /** Builds the initial indexes eagerly, like the previous startup behavior. */
    public static CodeIndexes forRoot(Path root) {
        return new CodeIndexes(root, new Snapshot(EndpointIndex.forRoot(root), BeanIndex.forRoot(root)));
    }

    /**
     * The current snapshot, rebuilt first if any scanned file was added, removed, or
     * modified since the last build. Unchanged sources return the same snapshot instance.
     */
    public synchronized Snapshot current() {
        return snapshot;
    }
}
