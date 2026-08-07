package io.github.subnocte.springwiring.index;

import io.github.subnocte.springwiring.scanner.SourceScanner;
import io.github.subnocte.springwiring.testwiring.TestWiringIndex;

import java.nio.file.Path;

/**
 * Holder for the test-wiring index that keeps it consistent with the test sources on
 * disk, exactly like {@link CodeIndexes} for production sources — the difference is what
 * it scans: {@link SourceScanner#scanTestSources} instead of {@link SourceScanner#scan}.
 * That difference is also why this is a separate class rather than folded into
 * {@link CodeIndexes.Snapshot}: fingerprinting production and test files together would
 * mean an edit to either side invalidates both, which is both wasteful (a test-only edit
 * rebuilding the six production-index tools' data) and wrong (a production-only edit
 * rebuilding test-wiring data that did not change).
 *
 * <p>Thin, {@link FreshIndexes}-backed specialization; see {@link TestIndexesRegistry} for
 * the per-root cache built on top of this (lazily, unlike {@link CodeIndexesRegistry}).
 */
public final class TestIndexes {

    private final FreshIndexes<TestWiringIndex> delegate;

    private TestIndexes(FreshIndexes<TestWiringIndex> delegate) {
        this.delegate = delegate;
    }

    /** Builds the initial test-wiring index eagerly by scanning {@code root} right now. */
    public static TestIndexes forRoot(Path root) {
        return new TestIndexes(FreshIndexes.forRoot(root, SourceScanner::scanTestSources, TestWiringIndex::build));
    }

    /** The directory this instance indexes. */
    public Path root() {
        return delegate.root();
    }

    /**
     * The current test-wiring index, rebuilt first if any scanned test file was added,
     * removed, or modified since the last build. Unchanged sources return the same
     * instance.
     */
    public TestWiringIndex current() {
        return delegate.current();
    }
}
