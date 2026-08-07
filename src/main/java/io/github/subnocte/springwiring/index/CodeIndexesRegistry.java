package io.github.subnocte.springwiring.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final Path defaultRoot;
    private final int maxRoots;

    /** Access-ordered so the eldest entry (iteration order) is the least recently used. */
    private final LinkedHashMap<Path, CodeIndexes> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    public CodeIndexesRegistry(Path defaultRoot) {
        this(defaultRoot, DEFAULT_MAX_ROOTS);
    }

    public CodeIndexesRegistry(Path defaultRoot, int maxRoots) {
        this.maxRoots = maxRoots;
        this.defaultRoot = normalize(defaultRoot);
        // Eager, like the pre-registry single-root behavior: a bad startup root should
        // fail fast at construction rather than surface as a mysterious first-call error.
        cache.put(this.defaultRoot, CodeIndexes.forRoot(this.defaultRoot));
    }

    public Path defaultRoot() {
        return defaultRoot;
    }

    /**
     * Returns the cached {@link CodeIndexes} for {@code root}, building it on first
     * request. If accepting this root would push the cache past {@link #maxRoots}, the
     * least recently used root other than {@link #defaultRoot()} is evicted and reported
     * in {@link Success#notices()}.
     */
    public synchronized Lookup forRoot(Path root) {
        Path normalized = normalize(root);
        Optional<String> invalid = validate(normalized);
        if (invalid.isPresent()) {
            return new Failure(invalid.get());
        }

        // cache.get() (not computeIfAbsent) so a hit is recorded as the most recently
        // used entry: HashMap.computeIfAbsent bypasses LinkedHashMap's access-order
        // bookkeeping on a hit, which would silently break the LRU eviction below.
        CodeIndexes indexes = cache.get(normalized);
        boolean isNew = indexes == null;
        if (isNew) {
            indexes = CodeIndexes.forRoot(normalized);
            cache.put(normalized, indexes);
        }

        List<String> notices = new ArrayList<>();
        if (isNew && cache.size() > maxRoots) {
            evictOneLeastRecentlyUsed(normalized).ifPresent(evicted ->
                    notices.add("Evicted root " + evicted + " (spring-wiring.max-roots=" + maxRoots
                            + " exceeded)"));
        }
        return new Success(indexes, normalized, notices);
    }

    /** All roots currently cached: the default plus any additional roots or materialized refs. */
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
