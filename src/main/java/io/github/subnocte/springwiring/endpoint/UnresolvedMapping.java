package io.github.subnocte.springwiring.endpoint;

/**
 * A mapping annotation that was found but could not be statically resolved into an
 * indexable endpoint. Surfaced to clients so the index never fails silently.
 *
 * @param filePath   absolute path to the source file
 * @param lineNumber 1-based line of the mapping's declaration
 * @param location   {@code fqcn#methodName}, or just {@code fqcn} for class-level mappings
 * @param reason     one of {@link #REASON_CONSTANT_REFERENCE},
 *                   {@link #REASON_NON_LITERAL_EXPRESSION}, {@link #REASON_UNSUPPORTED_PATTERN}
 */
public record UnresolvedMapping(String filePath, int lineNumber, String location, String reason) {

    /** Path or method attribute refers to a constant (field/name reference) instead of a string literal. */
    public static final String REASON_CONSTANT_REFERENCE = "constant-reference";

    /** Path or method attribute is an expression the static analysis does not evaluate (e.g. concatenation). */
    public static final String REASON_NON_LITERAL_EXPRESSION = "non-literal-expression";

    /** Path pattern uses syntax the matcher does not support yet (e.g. {@code *} / {@code **} wildcards). */
    public static final String REASON_UNSUPPORTED_PATTERN = "unsupported-pattern";
}
