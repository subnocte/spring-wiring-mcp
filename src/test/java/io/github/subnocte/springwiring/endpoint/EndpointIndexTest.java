package io.github.subnocte.springwiring.endpoint;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven tests for {@link EndpointIndex}, exercised purely against the AST layer
 * (no MCP/Spring Boot runtime involved). Fixtures live under
 * {@code src/test/resources/sample-project}.
 */
class EndpointIndexTest {

    private static EndpointIndex index;

    @BeforeAll
    static void buildIndex() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                EndpointIndexTest.class.getResource("/sample-project")).toURI());
        index = EndpointIndex.forRoot(root);
    }

    static Stream<Arguments> resolvableEndpoints() {
        return Stream.of(
                // class-level @RequestMapping combined with method-level shorthand mappings
                Arguments.of("GET", "/api/users", "UserController", "listUsers"),
                Arguments.of("GET", "/api/users/42", "UserController", "getUser"),
                Arguments.of("POST", "/api/users", "UserController", "createUser"),
                Arguments.of("PUT", "/api/users/42", "UserController", "updateUser"),
                Arguments.of("DELETE", "/api/users/42", "UserController", "deleteUser"),
                // lowercase method name should still resolve
                Arguments.of("get", "/api/users/42", "UserController", "getUser"),

                // @RestController with no class-level @RequestMapping
                Arguments.of("GET", "/health", "HealthController", "health"),

                // @Controller (not @RestController) using @RequestMapping(method = ...)
                Arguments.of("GET", "/legacy/ping", "LegacyController", "ping"),
                // @RequestMapping(method = {GET, POST}) matches either
                Arguments.of("GET", "/legacy/multi", "LegacyController", "multi"),
                Arguments.of("POST", "/legacy/multi", "LegacyController", "multi"),
                // @RequestMapping with no method attribute matches any HTTP method
                Arguments.of("GET", "/legacy/any", "LegacyController", "any"),
                Arguments.of("DELETE", "/legacy/any", "LegacyController", "any"),

                // multiple path variables in one pattern
                Arguments.of("GET", "/orders/7/items/99", "OrderController", "getItem")
        );
    }

    @ParameterizedTest(name = "{0} {1} -> {2}#{3}")
    @MethodSource("resolvableEndpoints")
    void resolvesKnownEndpoints(String method, String path, String expectedClassSimpleName, String expectedMethodName) {
        Optional<EndpointHandler> result = index.resolve(method, path);

        assertThat(result).isPresent();
        EndpointHandler handler = result.get();
        assertThat(handler.fqcn()).endsWith("." + expectedClassSimpleName);
        assertThat(handler.methodName()).isEqualTo(expectedMethodName);
        assertThat(handler.filePath()).endsWith(expectedClassSimpleName + ".java");
        assertThat(handler.lineNumber()).isGreaterThan(0);
    }

    @ParameterizedTest(name = "{0} {1} does not match /legacy/multi's methods")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PUT", "DELETE"})
    void methodMismatchIsNotResolved(String wrongMethod) {
        assertThat(index.resolve(wrongMethod, "/legacy/multi")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void unknownPathIsNotResolved() {
        assertThat(index.resolve("GET", "/does/not/exist")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void unknownPathReturnsClosestSuggestions() {
        var suggestions = index.suggestClosest("GET", "/api/user/42", 3);

        assertThat(suggestions).isNotEmpty();
        // "/api/user/42" is a near-miss of "/api/users/{id}"
        assertThat(suggestions.get(0).pathPattern()).isEqualTo("/api/users/{id}");
    }

    @org.junit.jupiter.api.Test
    void allReturnsEveryIndexedEndpoint() {
        assertThat(index.all()).hasSizeGreaterThanOrEqualTo(10);
    }

    @org.junit.jupiter.api.Test
    void literalSegmentWinsOverPathVariableRegardlessOfDeclarationOrder() {
        // SpecificityController declares /{id} before /special; Spring picks the literal.
        var special = index.resolve("GET", "/items/special");
        assertThat(special).isPresent();
        assertThat(special.get().methodName()).isEqualTo("special");

        var byId = index.resolve("GET", "/items/123");
        assertThat(byId).isPresent();
        assertThat(byId.get().methodName()).isEqualTo("byId");
    }

    @org.junit.jupiter.api.Test
    void unresolvableMappingsAreReportedNotSilentlySkipped() {
        var unresolved = index.unresolved();

        assertThat(unresolved).hasSize(4);

        // string concatenation stays unresolvable
        var concat = unresolved.stream()
                .filter(u -> u.reason().equals(UnresolvedMapping.REASON_NON_LITERAL_EXPRESSION))
                .findFirst().orElseThrow();
        assertThat(concat.location()).endsWith("ConstantPathController#all");
        assertThat(concat.filePath()).endsWith("ConstantPathController.java");
        assertThat(concat.lineNumber()).isGreaterThan(0);

        var wildcard = unresolved.stream()
                .filter(u -> u.reason().equals(UnresolvedMapping.REASON_UNSUPPORTED_PATTERN))
                .findFirst().orElseThrow();
        assertThat(wildcard.location()).endsWith("ConstantPathController#files");

        // path constant references are resolvable now; the only constant-reference left is
        // the shadowed method attribute in StaticImportMethodController
        var constantRefs = unresolved.stream()
                .filter(u -> u.reason().equals(UnresolvedMapping.REASON_CONSTANT_REFERENCE))
                .toList();
        assertThat(constantRefs).hasSize(1);
        assertThat(constantRefs.get(0).location()).endsWith("StaticImportMethodController#shadow");
    }

    @org.junit.jupiter.api.Test
    void sameClassConstantPathIsResolved() {
        var hit = index.resolve("GET", "/const/list");
        assertThat(hit).isPresent();
        assertThat(hit.get().methodName()).isEqualTo("list");
    }

    @org.junit.jupiter.api.Test
    void crossClassConstantPathsAreResolved() {
        // class-level: qualified field access ApiPaths.WIDGETS via regular import
        var list = index.resolve("GET", "/widgets");
        assertThat(list).isPresent();
        assertThat(list.get().fqcn()).endsWith(".WidgetController");
        assertThat(list.get().methodName()).isEqualTo("list");

        // method-level: NameExpr resolved through a static import
        var byId = index.resolve("GET", "/widgets/9");
        assertThat(byId).isPresent();
        assertThat(byId.get().methodName()).isEqualTo("byId");
        assertThat(byId.get().pathPattern()).isEqualTo("/widgets/{id}");
    }

    @org.junit.jupiter.api.Test
    void literalSiblingOfUnresolvedMappingIsStillIndexed() {
        var ok = index.resolve("GET", "/const/ok");
        assertThat(ok).isPresent();
        assertThat(ok.get().methodName()).isEqualTo("ok");
        // the constant-referenced mapping must not be indexed under a wrong path
        assertThat(index.resolve("GET", "/const")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void scannedFileCountIsExposed() {
        assertThat(index.scannedFileCount()).isGreaterThanOrEqualTo(6);
    }

    @org.junit.jupiter.api.Test
    void interfaceDeclaredMappingsAreIndexedForImplementingController() {
        var ping = index.resolve("GET", "/ping");
        assertThat(ping).isPresent();
        assertThat(ping.get().fqcn()).endsWith(".PingController");
        assertThat(ping.get().methodName()).isEqualTo("ping");
        // handler location points at the implementing method, not the interface
        assertThat(ping.get().filePath()).endsWith("PingController.java");
        assertThat(ping.get().lineNumber()).isGreaterThan(0);
    }

    @org.junit.jupiter.api.Test
    void implementationClassLevelMappingOverridesInterfaceClassLevel() {
        // ThingsApi declares /v1, ThingController declares /v2: Spring picks /v2, no concatenation
        var v2 = index.resolve("GET", "/v2/things/7");
        assertThat(v2).isPresent();
        assertThat(v2.get().fqcn()).endsWith(".ThingController");
        assertThat(v2.get().methodName()).isEqualTo("getThing");

        assertThat(index.resolve("GET", "/v1/things/7")).isEmpty();
        assertThat(index.resolve("GET", "/v2/v1/things/7")).isEmpty();

        var post = index.resolve("POST", "/v2/things");
        assertThat(post).isPresent();
        assertThat(post.get().methodName()).isEqualTo("createThing");
    }

    @org.junit.jupiter.api.Test
    void controllerImplementingUnknownInterfaceIsReportedNotSilentlyEmpty() {
        var missing = index.unresolved().stream()
                .filter(u -> u.reason().equals(UnresolvedMapping.REASON_INTERFACE_MAPPINGS_NOT_FOUND))
                .toList();

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).location()).endsWith("GeneratedStubController");
        assertThat(missing.get(0).filePath()).endsWith("GeneratedStubController.java");
    }

    @org.junit.jupiter.api.Test
    void staticImportedRequestMethodConstantsAreResolved() {
        var get = index.resolve("GET", "/si/echo");
        assertThat(get).isPresent();
        assertThat(get.get().methodName()).isEqualTo("echo");
        assertThat(index.resolve("POST", "/si/echo")).isEmpty();

        assertThat(index.resolve("GET", "/si/multi")).isPresent();
        assertThat(index.resolve("POST", "/si/multi")).isPresent();
        assertThat(index.resolve("DELETE", "/si/multi")).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void shadowedRequestMethodNameIsNotMisreadAsHttpMethod() {
        // PUT resolves to a same-class String constant, not RequestMethod.PUT:
        // must be reported unresolved, never indexed as HTTP PUT
        assertThat(index.resolve("PUT", "/si/shadow")).isEmpty();
    }
}
