package io.github.subnocte.springwiring.mcp;

import io.github.subnocte.springwiring.index.CodeIndexes;
import io.github.subnocte.springwiring.index.CodeIndexesRegistry;
import io.github.subnocte.springwiring.ref.RefMaterializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the {@code root} and {@code ref} parameters shared by every MCP tool into a
 * concrete {@link CodeIndexes} to query. {@code root} and {@code ref} are orthogonal and
 * composable: {@code ref} is always resolved against the repository at the <em>effective</em>
 * root (the {@code root} parameter, or the server's startup {@code code.root} if omitted).
 * Centralizing this here keeps root/ref handling byte-for-byte identical across all six
 * tools instead of six slightly-diverging reimplementations.
 */
public final class RootRefResolver {

    /** Outcome of {@link #resolve(String, String)}. */
    public sealed interface Result permits Success, Failure {
    }

    /**
     * @param indexes the index to query for this call
     * @param resolvedCommit non-null only when {@code ref} was supplied
     * @param notices self-reported side effects, e.g. an LRU eviction from the underlying registry
     */
    public record Success(CodeIndexes indexes, ResolvedCommit resolvedCommit, List<String> notices)
            implements Result {
    }

    /** @param reason human-readable explanation of why root/ref resolution failed */
    public record Failure(String reason) implements Result {
    }

    private final CodeIndexesRegistry registry;
    private final RefMaterializer refMaterializer;
    private final CodeIndexes fixedIndexes;

    public RootRefResolver(CodeIndexesRegistry registry, RefMaterializer refMaterializer) {
        this.registry = registry;
        this.refMaterializer = refMaterializer;
        this.fixedIndexes = null;
    }

    private RootRefResolver(CodeIndexes fixedIndexes) {
        this.registry = null;
        this.refMaterializer = null;
        this.fixedIndexes = fixedIndexes;
    }

    /**
     * A resolver backed by a single, already-built {@link CodeIndexes}, with no registry
     * or ref support. Exists so tool constructors written before root/ref existed keep
     * working unchanged: both parameters omitted behaves exactly as before, and supplying
     * either is reported as unsupported rather than silently ignored.
     */
    public static RootRefResolver fixed(CodeIndexes indexes) {
        return new RootRefResolver(indexes);
    }

    public Result resolve(String root, String ref) {
        if (fixedIndexes != null) {
            return resolveFixed(root, ref);
        }

        Path requestedRoot = (root == null ? registry.defaultRoot() : Path.of(root))
                .toAbsolutePath().normalize();

        if (ref == null) {
            return toResult(registry.forRoot(requestedRoot), null);
        }

        Optional<String> invalidRoot = CodeIndexesRegistry.validate(requestedRoot);
        if (invalidRoot.isPresent()) {
            return new Failure(invalidRoot.get());
        }
        RefMaterializer.Result materialized = refMaterializer.materialize(requestedRoot, ref);
        if (materialized instanceof RefMaterializer.Failure failure) {
            return new Failure(failure.reason());
        }
        RefMaterializer.Success refSuccess = (RefMaterializer.Success) materialized;
        ResolvedCommit resolvedCommit = new ResolvedCommit(ref, refSuccess.sha(), refSuccess.committedAt());
        return toResult(registry.forRoot(refSuccess.directory()), resolvedCommit);
    }

    /** Every directory this resolver currently knows about, classified for {@code indexStatus}. */
    public List<KnownRoot> knownRoots() {
        if (fixedIndexes != null) {
            return List.of(new KnownRoot(fixedIndexes.root().toString(), KnownRoot.KIND_DEFAULT));
        }
        Path defaultRoot = registry.defaultRoot();
        Path refsBase = refMaterializer.refsBaseDirectory();
        return registry.knownRoots().stream()
                .map(path -> new KnownRoot(path.toString(), classify(path, defaultRoot, refsBase)))
                .toList();
    }

    private static String classify(Path path, Path defaultRoot, Path refsBase) {
        if (path.equals(defaultRoot)) {
            return KnownRoot.KIND_DEFAULT;
        }
        if (path.startsWith(refsBase)) {
            return KnownRoot.KIND_REF;
        }
        return KnownRoot.KIND_ROOT;
    }

    private Result resolveFixed(String root, String ref) {
        if (root == null && ref == null) {
            return new Success(fixedIndexes, null, List.of());
        }
        return new Failure("This tool instance has no root/ref support (constructed with a "
                + "single fixed CodeIndexes, not a CodeIndexesRegistry/RefMaterializer).");
    }

    private static Result toResult(CodeIndexesRegistry.Lookup lookup, ResolvedCommit resolvedCommit) {
        if (lookup instanceof CodeIndexesRegistry.Failure failure) {
            return new Failure(failure.reason());
        }
        CodeIndexesRegistry.Success success = (CodeIndexesRegistry.Success) lookup;
        return new Success(success.indexes(), resolvedCommit, success.notices());
    }
}
