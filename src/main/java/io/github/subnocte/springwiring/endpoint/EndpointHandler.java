package io.github.subnocte.springwiring.endpoint;

/**
 * A single resolved REST endpoint: the HTTP method + path pattern it answers to,
 * and the handler method that implements it.
 *
 * @param httpMethod    HTTP method, e.g. {@code "GET"}, or {@code "ANY"} when the
 *                      mapping annotation did not restrict the method
 * @param pathPattern   Spring-style path pattern, e.g. {@code "/users/{id}"}
 * @param fqcn          fully-qualified name of the declaring class
 * @param methodName    simple name of the handler method
 * @param filePath      absolute path to the source file
 * @param lineNumber    1-based line number of the handler method declaration
 */
public record EndpointHandler(
        String httpMethod,
        String pathPattern,
        String fqcn,
        String methodName,
        String filePath,
        int lineNumber
) {

    /** {@code fqcn#methodName}, useful for display purposes. */
    public String qualifiedMethodName() {
        return fqcn + "#" + methodName;
    }
}
