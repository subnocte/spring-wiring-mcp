package io.github.subnocte.springwiring.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Git-independent cache of {@link TestIndexes} keyed by normalized root directory — the
 * test-wiring counterpart of {@link CodeIndexesRegistry}, sharing the same per-root
 * caching and LRU eviction mechanics via {@link IndexRegistry}. Two differences from the
 * production registry, both deliberate:
 *
 * <ul>
 *   <li>Built lazily: the default root's test sources are parsed only on the first call
 *       to {@code testWiring}/{@code testDoubleUsage}, not at server startup — a session
 *       that never calls either tool never pays for parsing every test file.
 *   <li>An entirely separate cache from {@link CodeIndexesRegistry}: it holds
 *       {@link TestIndexes} (which fingerprints {@code src/test}), so editing a
 *       production-only file never invalidates it and vice versa.
 * </ul>
 */
public final class TestIndexesRegistry {

    /** Outcome of {@link #forRoot(Path)}: either a usable index, or a reason it failed. */
    public sealed interface Lookup permits Success, Failure {
    }

    /**
     * @param indexes the cached (or freshly built) test index for {@code root}
     * @param root the normalized root the index was built for
     * @param notices self-reported side effects of this lookup, e.g. an LRU eviction
     */
    public record Success(TestIndexes indexes, Path root, List<String> notices) implements Lookup {
    }

    /** @param reason human-readable explanation of why {@code root} could not be indexed */
    public record Failure(String reason) implements Lookup {
    }

    private final IndexRegistry<TestIndexes> delegate;

    public TestIndexesRegistry(Path defaultRoot, int maxRoots) {
        // Lazy, unlike CodeIndexesRegistry: a session that never touches a test-wiring
        // tool should never pay for parsing test sources at startup.
        this.delegate = new IndexRegistry<>(defaultRoot, maxRoots, TestIndexes::forRoot, false);
    }

    public Path defaultRoot() {
        return delegate.defaultRoot();
    }

    /**
     * Returns the cached {@link TestIndexes} for {@code root}, building it on first
     * request (including, on this registry, the first request for the default root
     * itself). If accepting this root would push the cache past {@code maxRoots}, the
     * least recently used root other than {@link #defaultRoot()} is evicted and reported
     * in {@link Success#notices()}.
     */
    public synchronized Lookup forRoot(Path root) {
        return toLookup(delegate.forRoot(root));
    }

    /** All roots currently cached: the default (once built) plus any additional roots or materialized refs. */
    public synchronized List<Path> knownRoots() {
        return delegate.knownRoots();
    }

    /** Whether {@code root} could be handed to {@link #forRoot(Path)} successfully. */
    public static Optional<String> validate(Path root) {
        return IndexRegistry.validate(root);
    }

    private static Lookup toLookup(IndexRegistry.Lookup<TestIndexes> lookup) {
        if (lookup instanceof IndexRegistry.Failure<TestIndexes> failure) {
            return new Failure(failure.reason());
        }
        IndexRegistry.Success<TestIndexes> success = (IndexRegistry.Success<TestIndexes>) lookup;
        return new Success(success.indexes(), success.root(), success.notices());
    }
}
