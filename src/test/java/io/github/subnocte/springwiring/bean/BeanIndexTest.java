package io.github.subnocte.springwiring.bean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BeanIndex}, exercised purely against the AST layer (no MCP/Spring Boot
 * runtime involved). Fixtures live under {@code src/test/resources/sample-project},
 * package {@code com.example.sample.beans}.
 */
class BeanIndexTest {

    private static final String PKG = "com.example.sample.beans.";

    private static BeanIndex index;

    @BeforeAll
    static void buildIndex() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                BeanIndexTest.class.getResource("/sample-project")).toURI());
        index = BeanIndex.forRoot(root);
    }

    private static BeanDefinition beanNamed(String fqcn) {
        return index.allBeans().stream()
                .filter(b -> b.fqcn().equals(fqcn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such bean: " + fqcn));
    }

    private static BeanDependencies depsOf(String fqcn) {
        return index.dependenciesOf(fqcn).orElseThrow(() -> new AssertionError("not a bean: " + fqcn));
    }

    private static BeanEdge edge(BeanDependencies deps, String fieldName) {
        return deps.edges().stream()
                .filter(e -> e.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such edge: " + fieldName));
    }

    @Test
    void allBeansContainsEveryStereotypeAnnotatedClass() {
        List<String> fqcns = index.allBeans().stream().map(BeanDefinition::fqcn).toList();

        assertThat(fqcns).contains(
                PKG + "NotificationController",
                PKG + "NotificationService",
                PKG + "DefaultGreetingService",
                PKG + "StripeGateway",
                PKG + "MockGateway",
                PKG + "CheckoutService",
                PKG + "CsvExportFormat",
                PKG + "JsonExportFormat",
                PKG + "ReportService",
                PKG + "RedisCacheProvider",
                PKG + "InMemoryCacheProvider",
                PKG + "PaymentAuditor",
                PKG + "AppConfig",
                PKG + "dup.Formatter",
                PKG + "dup2.Formatter",
                PKG + "AuditMapper",
                PKG + "OrderSearchRepository",
                PKG + "RateLimiter");
    }

    @Test
    void allBeansIncludesExistingEndpointFixtureControllers() {
        assertThat(index.allBeans())
                .extracting(BeanDefinition::fqcn)
                .contains("com.example.sample.controller.UserController");
    }

    @Test
    void allBeansExcludesNonBeanTypes() {
        assertThat(index.allBeans())
                .extracting(BeanDefinition::fqcn)
                .doesNotContain(
                        PKG + "PlainHelper",
                        PKG + "GreetingService",
                        PKG + "PaymentGateway",
                        PKG + "ExportFormat",
                        PKG + "CacheProvider",
                        PKG + "SignatureVerifier");
    }

    @Test
    void stereotypesAreReportedPerBean() {
        assertThat(beanNamed(PKG + "NotificationController").stereotype()).isEqualTo("RestController");
        assertThat(beanNamed(PKG + "NotificationService").stereotype()).isEqualTo("Service");
        assertThat(beanNamed(PKG + "CsvExportFormat").stereotype()).isEqualTo("Component");
        assertThat(beanNamed(PKG + "AppConfig").stereotype()).isEqualTo("Configuration");
        assertThat(beanNamed(PKG + "RateLimiter").stereotype()).isEqualTo("Bean");
    }

    @Test
    void terminalBeansAreFlagged() {
        BeanDefinition mapper = beanNamed(PKG + "AuditMapper");
        assertThat(mapper.terminal()).isTrue();
        assertThat(mapper.stereotype()).isEqualTo("Mapper");

        BeanDefinition repository = beanNamed(PKG + "OrderSearchRepository");
        assertThat(repository.terminal()).isTrue();
    }

    @Test
    void repositoryBasesAreFollowedTransitively() {
        // BaseAuditRepository extends JpaRepository directly; ExtendedAuditRepository
        // only through the project-local base - both are framework-implemented
        BeanDefinition base = beanNamed(PKG + "BaseAuditRepository");
        assertThat(base.terminal()).isTrue();

        BeanDefinition extended = beanNamed(PKG + "ExtendedAuditRepository");
        assertThat(extended.terminal()).isTrue();
        assertThat(extended.stereotype()).isEqualTo("JpaRepository");
    }

    @Test
    void notificationControllerHasOneResolvedConcreteEdge() {
        BeanDependencies deps = depsOf(PKG + "NotificationController");

        assertThat(deps.edges()).hasSize(1);
        BeanEdge e = deps.edges().get(0);
        assertThat(e.fieldName()).isEqualTo("notificationService");
        assertThat(e.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(e.kind()).isEqualTo(BeanEdge.KIND_CONCRETE);
        assertThat(e.target()).isNotNull();
        assertThat(e.target().fqcn()).isEqualTo(PKG + "NotificationService");
    }

    @Test
    void notificationServiceFiltersIntFieldAndResolvesLombokRequiredArgsFields() {
        BeanDependencies deps = depsOf(PKG + "NotificationService");

        assertThat(deps.edges()).hasSize(2);

        BeanEdge auditMapper = edge(deps, "auditMapper");
        assertThat(auditMapper.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(auditMapper.kind()).isEqualTo(BeanEdge.KIND_TERMINAL);
        assertThat(auditMapper.target().fqcn()).isEqualTo(PKG + "AuditMapper");

        BeanEdge greetingService = edge(deps, "greetingService");
        assertThat(greetingService.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(greetingService.kind()).isEqualTo(BeanEdge.KIND_SINGLE_IMPLEMENTATION);
        assertThat(greetingService.target().fqcn()).isEqualTo(PKG + "DefaultGreetingService");
    }

    @Test
    void defaultGreetingServiceResolvesPrimaryImplementation() {
        BeanDependencies deps = depsOf(PKG + "DefaultGreetingService");

        BeanEdge paymentGateway = edge(deps, "paymentGateway");
        assertThat(paymentGateway.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(paymentGateway.kind()).isEqualTo(BeanEdge.KIND_PRIMARY);
        assertThat(paymentGateway.target().fqcn()).isEqualTo(PKG + "StripeGateway");
    }

    @Test
    void checkoutServiceFiltersStaticFinalAndCoversQualifierCollectionAndLibraryType() {
        BeanDependencies deps = depsOf(PKG + "CheckoutService");

        assertThat(deps.edges()).hasSize(3);

        BeanEdge gateway = edge(deps, "gateway");
        assertThat(gateway.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(gateway.kind()).isEqualTo(BeanEdge.KIND_QUALIFIER);
        assertThat(gateway.target().fqcn()).isEqualTo(PKG + "MockGateway");

        BeanEdge formats = edge(deps, "formats");
        assertThat(formats.status()).isEqualTo(BeanEdge.STATUS_UNRESOLVED);
        assertThat(formats.reason()).isEqualTo(BeanEdge.REASON_COLLECTION_INJECTION);

        BeanEdge objectMapper = edge(deps, "objectMapper");
        assertThat(objectMapper.status()).isEqualTo(BeanEdge.STATUS_NOT_A_SCANNED_BEAN);
    }

    @Test
    void reportServiceCoversAllThreeUnresolvedInterfaceReasons() {
        BeanDependencies deps = depsOf(PKG + "ReportService");

        BeanEdge exportFormat = edge(deps, "exportFormat");
        assertThat(exportFormat.status()).isEqualTo(BeanEdge.STATUS_UNRESOLVED);
        assertThat(exportFormat.reason()).isEqualTo(BeanEdge.REASON_MULTIPLE_CANDIDATES);
        assertThat(exportFormat.candidates())
                .extracting(BeanDefinition::fqcn)
                .containsExactlyInAnyOrder(PKG + "CsvExportFormat", PKG + "JsonExportFormat");

        BeanEdge cacheProvider = edge(deps, "cacheProvider");
        assertThat(cacheProvider.status()).isEqualTo(BeanEdge.STATUS_UNRESOLVED);
        assertThat(cacheProvider.reason()).isEqualTo(BeanEdge.REASON_CONDITIONAL_CANDIDATES);
        assertThat(cacheProvider.candidates())
                .extracting(BeanDefinition::fqcn)
                .containsExactlyInAnyOrder(PKG + "RedisCacheProvider", PKG + "InMemoryCacheProvider");

        BeanEdge signatureVerifier = edge(deps, "signatureVerifier");
        assertThat(signatureVerifier.status()).isEqualTo(BeanEdge.STATUS_UNRESOLVED);
        assertThat(signatureVerifier.reason()).isEqualTo(BeanEdge.REASON_NO_IMPLEMENTATION_FOUND);
    }

    @Test
    void paymentAuditorResolvesBeanMethodTarget() {
        BeanDependencies deps = depsOf(PKG + "PaymentAuditor");

        BeanEdge rateLimiter = edge(deps, "rateLimiter");
        assertThat(rateLimiter.status()).isEqualTo(BeanEdge.STATUS_RESOLVED);
        assertThat(rateLimiter.kind()).isEqualTo(BeanEdge.KIND_CONCRETE);
        assertThat(rateLimiter.target().fqcn()).isEqualTo(PKG + "RateLimiter");
        assertThat(rateLimiter.target().stereotype()).isEqualTo("Bean");
    }

    @Test
    void terminalBeanHasEmptyEdgesAndNonBeanIsAbsent() {
        BeanDependencies mapperDeps = depsOf(PKG + "AuditMapper");
        assertThat(mapperDeps.edges()).isEmpty();

        assertThat(index.dependenciesOf(PKG + "PlainHelper")).isEqualTo(Optional.empty());
    }

    @Test
    void findByNameMatchesSimpleNameFqcnAndAmbiguity() {
        assertThat(index.findByName("NotificationService")).hasSize(1);
        assertThat(index.findByName(PKG + "NotificationService")).hasSize(1);
        assertThat(index.findByName("Formatter")).hasSize(2);
        assertThat(index.findByName("NoSuchBean")).isEmpty();
    }

    @Test
    void unresolvedInjectionCountByReasonAggregatesAcrossAllBeans() {
        assertThat(index.unresolvedInjectionCountByReason()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(
                        BeanEdge.REASON_COLLECTION_INJECTION, 1L,
                        BeanEdge.REASON_MULTIPLE_CANDIDATES, 1L,
                        BeanEdge.REASON_CONDITIONAL_CANDIDATES, 1L,
                        BeanEdge.REASON_NO_IMPLEMENTATION_FOUND, 1L));
    }

    @Test
    void beanDefinitionCarriesFilePathAndLineNumber() {
        BeanDefinition service = beanNamed(PKG + "NotificationService");
        assertThat(service.filePath()).endsWith("NotificationService.java");
        assertThat(service.lineNumber()).isGreaterThan(0);
    }

    @Test
    void dependentsOfConcreteTargetFindsResolvedControllerEdge() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "NotificationService");

        assertThat(dependents).hasSize(1);
        BeanIndex.Dependent dependent = dependents.get(0);
        assertThat(dependent.bean().fqcn()).isEqualTo(PKG + "NotificationController");
        assertThat(dependent.edge().fieldName()).isEqualTo("notificationService");
        assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_TARGET);
    }

    @Test
    void dependentsOfSingleImplementationTargetIncludesResolvingBean() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "DefaultGreetingService");

        assertThat(dependents).anySatisfy(dependent -> {
            assertThat(dependent.bean().fqcn()).isEqualTo(PKG + "NotificationService");
            assertThat(dependent.edge().fieldName()).isEqualTo("greetingService");
            assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_TARGET);
        });
    }

    @Test
    void dependentsOfInterfaceDeclaredTypeFindsDeclaringBean() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "GreetingService");

        assertThat(dependents).hasSize(1);
        BeanIndex.Dependent dependent = dependents.get(0);
        assertThat(dependent.bean().fqcn()).isEqualTo(PKG + "NotificationService");
        assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_DECLARED_TYPE);
    }

    @Test
    void dependentsOfPaymentGatewayFindsBothDeclaredTypeSites() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "PaymentGateway");

        assertThat(dependents).hasSize(2);
        assertThat(dependents).extracting(d -> d.bean().fqcn())
                .containsExactlyInAnyOrder(PKG + "DefaultGreetingService", PKG + "CheckoutService");
        assertThat(dependents).allSatisfy(dependent ->
                assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_DECLARED_TYPE));
    }

    @Test
    void dependentsOfUnresolvedCandidateIncludesCandidateSite() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "CsvExportFormat");

        assertThat(dependents).hasSize(1);
        BeanIndex.Dependent dependent = dependents.get(0);
        assertThat(dependent.bean().fqcn()).isEqualTo(PKG + "ReportService");
        assertThat(dependent.edge().fieldName()).isEqualTo("exportFormat");
        assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_CANDIDATE);
    }

    @Test
    void dependentsOfTerminalMapperTargetFindsResolvedEdge() {
        List<BeanIndex.Dependent> dependents = index.dependentsOf(PKG + "AuditMapper");

        assertThat(dependents).hasSize(1);
        BeanIndex.Dependent dependent = dependents.get(0);
        assertThat(dependent.bean().fqcn()).isEqualTo(PKG + "NotificationService");
        assertThat(dependent.via()).isEqualTo(BeanIndex.Dependent.VIA_TARGET);
    }

    @Test
    void findTypeByNameMatchesInterfacesFqcnAndAmbiguityAndAbsence() {
        assertThat(index.findTypeByName("GreetingService")).containsExactly(PKG + "GreetingService");
        assertThat(index.findTypeByName(PKG + "GreetingService")).containsExactly(PKG + "GreetingService");
        assertThat(index.findTypeByName("Formatter")).hasSize(2);
        assertThat(index.findTypeByName("NotificationService")).containsExactly(PKG + "NotificationService");
        assertThat(index.findTypeByName("NoSuchType")).isEmpty();
    }

    @Test
    void dependentsOfBeanWithNoDependentsIsEmpty() {
        assertThat(index.dependentsOf(PKG + "ReportService")).isEmpty();
    }
}
