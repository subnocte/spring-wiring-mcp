package io.github.subnocte.springwiring.scanner;

/**
 * A source file the parser could not process. A file that fails to parse is invisible
 * to the whole analysis — its endpoints and beans simply don't exist in the index — so
 * every failure is reported instead of silently narrowing coverage.
 *
 * @param filePath absolute path of the file
 * @param reason   parser message, trimmed to its first problem line
 */
public record ParseFailure(String filePath, String reason) {
}
