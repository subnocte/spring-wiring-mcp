package io.github.subnocte.springwiring.scanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recursively collects {@code .java} source files under a given root directory.
 *
 * <p>Directories that never contain production sources are pruned without being
 * descended into, so pointing the scanner at a repository root (even a monorepo
 * with a {@code node_modules} tree) stays cheap and, more importantly, correct:
 * generated or copied sources under build output would otherwise be indexed as
 * endpoints that do not exist in {@code src/main}.
 */
public final class SourceScanner {

    /** Build output and dependency directories: contain only generated/copied sources. */
    private static final Set<String> EXCLUDED_DIR_NAMES =
            Set.of("node_modules", "build", "target", "out", "bin");

    private SourceScanner() {
    }

    /**
     * Walks {@code root} and returns every regular file ending in {@code .java},
     * skipping hidden directories (name starting with {@code .}), build output and
     * dependency directories ({@code build}, {@code target}, {@code out}, {@code bin},
     * {@code node_modules}), and test source sets ({@code src/<name containing "test">},
     * e.g. {@code src/test}, {@code src/integrationTest}, {@code src/testFixtures}).
     * The root itself is always scanned, whatever its name.
     *
     * @param root directory to scan; must exist
     * @return sorted list of absolute paths to Java source files
     */
    public static List<Path> scan(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        List<Path> sources = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isExcluded(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.toString().endsWith(".java")) {
                        sources.add(file.toAbsolutePath());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan source root: " + root, e);
        }
        sources.sort(null);
        return List.copyOf(sources);
    }

    private static boolean isExcluded(Path dir) {
        String name = dir.getFileName().toString();
        if (name.startsWith(".") || EXCLUDED_DIR_NAMES.contains(name)) {
            return true;
        }
        Path parent = dir.getParent();
        return parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals("src")
                && name.toLowerCase(Locale.ROOT).contains("test");
    }
}
