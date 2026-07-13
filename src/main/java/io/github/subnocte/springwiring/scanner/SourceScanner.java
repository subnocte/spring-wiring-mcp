package io.github.subnocte.springwiring.scanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Recursively collects {@code .java} source files under a given root directory.
 */
public final class SourceScanner {

    private SourceScanner() {
    }

    /**
     * Walks {@code root} and returns every regular file ending in {@code .java}.
     *
     * @param root directory to scan; must exist
     * @return sorted list of absolute paths to Java source files
     */
    public static List<Path> scan(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toAbsolutePath)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan source root: " + root, e);
        }
    }
}
