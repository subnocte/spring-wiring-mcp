package io.github.subnocte.springwiring.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Git-independent cache of {@link CodeIndexes} keyed by normalized root directory. This is
 * the core that lets tools analyze a directory other than the server's startup
 * {@code code.root}: each requested root gets its own lazily-built, cached index, invalid
 * roots are reported instead of thrown, and the number of simultaneously cached roots is
 * bounded so a long-running server pointed at many ad hoc directories does not grow
 * memory forever. The default root (the server's startup {@code code.root}) is never
 * evicted; it is built eagerly at construction, matching the previous single-root
 * behavior.
 *
 * <p>This class knows nothing about git or refs — that translation lives one layer up,
 * in the ref materializer that turns a git ref into a plain directory before calling
 * {@link #forRoot(Path)}.
 *
 * <p>Thin, public-surface-preserving specialization of {@link IndexRegistry} for
 * {@link CodeIndexes}, built eagerly at construction (a bad startup root fails fast).
 */
public final class CodeIndexesRegistry {

    /** Default cap on simultaneously cached roots; overridable via constructor. */
    public static final int DEFAULT_MAX_ROOTS = 8;

    /** Outcome of {@link #forRoot(Path)}: either a usable index, or a reason it failed. */
    public sealed interface Lookup permits Success, Failure {
    }

    /**
     * @param indexes the cached (or freshly built) index for {@code root}
     * @param root the normalized root the index was built for
     * @param notices self-reported side effects of this lookup, e.g. an LRU eviction
     */
    public record Success(CodeIndexes indexes, Path root, List<String> notices) implements Lookup {
    }

    /** @param reason human-readable explanation of why {@code root} could not be indexed */
    public record Failure(String reason) implements Lookup {
    }

    private final IndexRegistry<CodeIndexes> delegate;

    public CodeIndexesRegistry(Path defaultRoot) {
        this(defaultRoot, DEFAULT_MAX_ROOTS);
    }

    public CodeIndexesRegistry(Path defaultRoot, int maxRoots) {
        // Eager, like the pre-registry single-root behavior: a bad startup root should
        // fail fast at construction rather than surface as a mysterious first-call error.
        this.delegate = new IndexRegistry<>(defaultRoot, maxRoots, CodeIndexes::forRoot, true);
    }

    public Path defaultRoot() {
        return delegate.defaultRoot();
    }

    /**
     * Returns the cached {@link CodeIndexes} for {@code root}, building it on first
     * request. If accepting this root would push the cache past {@link #DEFAULT_MAX_ROOTS}
     * (or the constructor-supplied {@code maxRoots}), the least recently used root other
     * than {@link #defaultRoot()} is evicted and reported in {@link Success#notices()}.
     */
    public synchronized Lookup forRoot(Path root) {
        return toLookup(delegate.forRoot(root));
    }

    /** All roots currently cached: the default plus any additional roots or materialized refs. */
    public synchronized List<Path> knownRoots() {
        return delegate.knownRoots();
    }

    /**
     * Whether {@code root} could be handed to {@link #forRoot(Path)} successfully. Exposed
     * so callers that need to validate a root before doing something else with it first
     * (e.g. resolving a git ref against it) can reuse this exact check instead of
     * duplicating it.
     */
    public static Optional<String> validate(Path root) {
        return IndexRegistry.validate(root);
    }

    private static Lookup toLookup(IndexRegistry.Lookup<CodeIndexes> lookup) {
        if (lookup instanceof IndexRegistry.Failure<CodeIndexes> failure) {
            return new Failure(failure.reason());
        }
        IndexRegistry.Success<CodeIndexes> success = (IndexRegistry.Success<CodeIndexes>) lookup;
        return new Success(success.indexes(), success.root(), success.notices());
    }
}
