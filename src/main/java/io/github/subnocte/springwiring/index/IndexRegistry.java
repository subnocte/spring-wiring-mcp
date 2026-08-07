package io.github.subnocte.springwiring.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Generic, git-independent cache of built index holders keyed by normalized root
 * directory: each requested root gets its own holder, built once via a caller-supplied
 * factory and cached; invalid roots are reported instead of thrown; and the number of
 * simultaneously cached roots is bounded so a long-running server pointed at many ad hoc
 * directories does not grow memory forever.
 *
 * <p>Whether the default root is built immediately at construction (fail-fast, matching
 * the pre-registry single-root behavior) or lazily on its first {@link #forRoot(Path)}
 * call like any other root is a per-registry choice ({@code eager}): production indexes
 * ({@link CodeIndexesRegistry}) stay eager so a bad startup {@code code.root} fails fast
 * and the six index-backed tools never pay a first-call surprise; test-source indexes
 * stay lazy so a session that never calls a test-wiring tool never pays the cost of
 * parsing every test file at startup.
 *
 * <p>This class knows nothing about git or refs — that translation lives one layer up,
 * in the ref materializer that turns a git ref into a plain directory before calling
 * {@link #forRoot(Path)}.
 *
 * @param <T> the cached per-root index holder type (e.g. {@link CodeIndexes})
 */
public final class IndexRegistry<T> {

    /** Outcome of {@link #forRoot(Path)}: either a usable index, or a reason it failed. */
    public sealed interface Lookup<T> permits Success, Failure {
    }

    /**
     * @param indexes the cached (or freshly built) index holder for {@code root}
     * @param root the normalized root the index was built for
     * @param notices self-reported side effects of this lookup, e.g. an LRU eviction
     */
    public record Success<T>(T indexes, Path root, List<String> notices) implements Lookup<T> {
    }

    /** @param reason human-readable explanation of why {@code root} could not be indexed */
    public record Failure<T>(String reason) implements Lookup<T> {
    }

    private final Path defaultRoot;
    private final int maxRoots;
    private final Function<Path, T> factory;

    /** Access-ordered so the eldest entry (iteration order) is the least recently used. */
    private final LinkedHashMap<Path, T> cache = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * @param defaultRoot the root that is never evicted
     * @param maxRoots    cap on simultaneously cached roots
     * @param factory     builds the index holder for a normalized root on first request
     * @param eager       true to build {@code defaultRoot} immediately at construction
     *                    (a bad root fails fast here rather than on first use); false to
     *                    build it lazily on first {@link #forRoot(Path)} like any other root
     */
    public IndexRegistry(Path defaultRoot, int maxRoots, Function<Path, T> factory, boolean eager) {
        this.maxRoots = maxRoots;
        this.factory = factory;
        this.defaultRoot = normalize(defaultRoot);
        if (eager) {
            cache.put(this.defaultRoot, factory.apply(this.defaultRoot));
        }
    }

    public Path defaultRoot() {
        return defaultRoot;
    }

    /**
     * Returns the cached index holder for {@code root}, building it on first request. If
     * accepting this root would push the cache past {@code maxRoots}, the least recently
     * used root other than {@link #defaultRoot()} is evicted and reported in
     * {@link Success#notices()}.
     */
    public synchronized Lookup<T> forRoot(Path root) {
        Path normalized = normalize(root);
        Optional<String> invalid = validate(normalized);
        if (invalid.isPresent()) {
            return new Failure<>(invalid.get());
        }

        // cache.get() (not computeIfAbsent) so a hit is recorded as the most recently
        // used entry: HashMap.computeIfAbsent bypasses LinkedHashMap's access-order
        // bookkeeping on a hit, which would silently break the LRU eviction below.
        T indexes = cache.get(normalized);
        boolean isNew = indexes == null;
        if (isNew) {
            indexes = factory.apply(normalized);
            cache.put(normalized, indexes);
        }

        List<String> notices = new ArrayList<>();
        if (isNew && cache.size() > maxRoots) {
            evictOneLeastRecentlyUsed(normalized).ifPresent(evicted ->
                    notices.add("Evicted root " + evicted + " (spring-wiring.max-roots=" + maxRoots
                            + " exceeded)"));
        }
        return new Success<>(indexes, normalized, notices);
    }

    /** All roots currently cached: the default (once built) plus any additional roots or materialized refs. */
    public synchronized List<Path> knownRoots() {
        return List.copyOf(cache.keySet());
    }

    private Optional<Path> evictOneLeastRecentlyUsed(Path justAdded) {
        Iterator<Path> it = cache.keySet().iterator();
        while (it.hasNext()) {
            Path candidate = it.next();
            if (!candidate.equals(defaultRoot) && !candidate.equals(justAdded)) {
                it.remove();
                return Optional.of(candidate);
            }
        }
        // Only the default root and the entry just added exist: nothing evictable.
        return Optional.empty();
    }

    /**
     * Whether {@code root} could be handed to {@link #forRoot(Path)} successfully. Exposed
     * so callers that need to validate a root before doing something else with it first
     * (e.g. resolving a git ref against it) can reuse this exact check instead of
     * duplicating it.
     */
    public static Optional<String> validate(Path root) {
        if (!Files.exists(root)) {
            return Optional.of("Root does not exist: " + root);
        }
        if (!Files.isDirectory(root)) {
            return Optional.of("Root is not a directory: " + root);
        }
        return Optional.empty();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
