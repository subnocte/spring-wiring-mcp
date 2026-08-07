package io.github.subnocte.springwiring.testwiring;

import io.github.subnocte.springwiring.scanner.SourceScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link TestWiringIndex}, exercised purely against the AST layer (no MCP/Spring
 * Boot runtime involved). Fixtures live under {@code src/test/resources/sample-project},
 * package {@code com.example.sample.testwiring} (a test source set of the sample project
 * itself, collected via {@link SourceScanner#scanTestSources}).
 */
class TestWiringIndexTest {

    private static final String PKG = "com.example.sample.testwiring.";

    private static TestWiringIndex index;

    @BeforeAll
    static void buildIndex() throws URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(
                TestWiringIndexTest.class.getResource("/sample-project")).toURI());
        List<Path> testFiles = SourceScanner.scanTestSources(root);
        index = TestWiringIndex.build(testFiles);
    }

    private static TestWiringIndex.TestClassWiring wiringOf(String simpleName) {
        return index.wiringOf(PKG + simpleName)
                .orElseThrow(() -> new AssertionError("no such test class: " + simpleName));
    }

    @Test
    void mockAndInjectMocksFieldsAreClassified() {
        var wiring = wiringOf("MockitoExtensionFieldsTest");

        assertThat(wiring.mocked()).hasSize(1);
        var mock = wiring.mocked().get(0);
        assertThat(mock.fieldName()).isEqualTo("gateway");
        assertThat(mock.typeFqcn()).isEqualTo("com.example.sample.beans.PaymentGateway");
        assertThat(mock.kind()).isEqualTo(TestWiringIndex.MockKind.MOCK);
        assertThat(mock.nestedScope()).isNull();

        assertThat(wiring.realSubjects()).hasSize(1);
        var subject = wiring.realSubjects().get(0);
        assertThat(subject.fieldName()).isEqualTo("subject");
        assertThat(subject.typeFqcn()).isEqualTo("com.example.sample.beans.CheckoutService");
        assertThat(subject.source()).isEqualTo("@InjectMocks");
        assertThat(subject.nestedScope()).isNull();

        assertThat(wiring.slice().extendWith()).contains("org.mockito.junit.jupiter.MockitoExtension");
    }

    @Test
    void mockitoBeanAndMockitoSpyBeanFieldsAreClassified() {
        var wiring = wiringOf("MockitoBeanFieldsTest");

        assertThat(wiring.mocked())
                .extracting(TestWiringIndex.MockedDeclaration::kind)
                .containsExactlyInAnyOrder(
                        TestWiringIndex.MockKind.MOCKITO_BEAN, TestWiringIndex.MockKind.MOCKITO_SPY_BEAN);
    }

    @Test
    void legacyMockBeanAndSpyBeanFieldsAreClassified() {
        var wiring = wiringOf("LegacyMockBeanFieldsTest");

        assertThat(wiring.mocked())
                .extracting(TestWiringIndex.MockedDeclaration::kind)
                .containsExactlyInAnyOrder(TestWiringIndex.MockKind.MOCK_BEAN, TestWiringIndex.MockKind.SPY_BEAN);
    }

    @Test
    void webMvcSliceCapturesControllersArgument() {
        var wiring = wiringOf("WebMvcSliceTest");

        assertThat(wiring.slice().classAnnotations())
                .anyMatch(a -> a.contains("WebMvcTest") && a.contains("ThingController"));
    }

    @Test
    void mockStaticInMethodIsScopedToTheMethodName() {
        var wiring = wiringOf("MockStaticInMethodTest");

        assertThat(wiring.staticMocks()).hasSize(1);
        var staticMock = wiring.staticMocks().get(0);
        assertThat(staticMock.typeFqcn()).isEqualTo("com.example.sample.beans.StripeGateway");
        assertThat(staticMock.scope()).isEqualTo("freezesStripeGatewayForThisTestOnly");
        assertThat(staticMock.nestedScope()).isNull();
    }

    @Test
    void mockStaticInBeforeEachIsScopedToBeforeEach() {
        var wiring = wiringOf("MockStaticInBeforeEachTest");

        assertThat(wiring.staticMocks()).hasSize(1);
        assertThat(wiring.staticMocks().get(0).scope()).isEqualTo("@BeforeEach");
    }

    @Test
    void mockStaticAsFieldIsScopedToTheFieldName() {
        var wiring = wiringOf("MockStaticAsFieldTest");

        assertThat(wiring.staticMocks()).hasSize(1);
        assertThat(wiring.staticMocks().get(0).scope()).isEqualTo("frozenGateway");
    }

    @Test
    void nestedTestClassesAggregateUnderTheTopLevelClassWithNestedScope() {
        var wiring = wiringOf("NestedNotificationTests");

        assertThat(wiring.mocked()).hasSize(2);
        var outer = wiring.mocked().stream().filter(m -> m.fieldName().equals("auditor")).findFirst().orElseThrow();
        assertThat(outer.nestedScope()).isNull();
        var nested = wiring.mocked().stream()
                .filter(m -> m.fieldName().equals("nestedOnlyAuditor")).findFirst().orElseThrow();
        assertThat(nested.nestedScope()).isEqualTo("ValidateRegistrationFormTests");

        assertThat(wiring.realSubjects()).hasSize(1);
        assertThat(wiring.realSubjects().get(0).fieldName()).isEqualTo("subject");
        assertThat(wiring.realSubjects().get(0).nestedScope()).isNull();
    }

    @Test
    void beforeEachFieldAssignmentOfNewIsARealSubject() {
        var wiring = wiringOf("BeforeEachConstructedSubjectTest");

        assertThat(wiring.realSubjects()).hasSize(1);
        var subject = wiring.realSubjects().get(0);
        assertThat(subject.fieldName()).isEqualTo("subject");
        assertThat(subject.typeFqcn()).isEqualTo("com.example.sample.beans.DefaultGreetingService");
        assertThat(subject.source()).isEqualTo("new");
    }

    @Test
    void fieldInitializerFormsOfMockSpyAndNewAreClassified() {
        var wiring = wiringOf("FieldInitializerConstructionTest");

        assertThat(wiring.mocked())
                .extracting(TestWiringIndex.MockedDeclaration::fieldName, TestWiringIndex.MockedDeclaration::kind)
                .contains(
                        tuple("mockedGateway", TestWiringIndex.MockKind.MOCK_CALL),
                        tuple("spiedGateway", TestWiringIndex.MockKind.SPY_CALL));
        assertThat(wiring.realSubjects())
                .anyMatch(r -> r.fieldName().equals("realSubject") && r.source().equals("new"));
    }

    @Test
    void unknownAnnotationAndUnresolvedMockStaticLiteralAreSelfReported() {
        var wiring = wiringOf("UnknownAnnotationAndUnresolvedLiteralTest");

        assertThat(wiring.mocked()).isEmpty();
        assertThat(wiring.staticMocks()).isEmpty();
        assertThat(wiring.unresolved()).hasSize(2);
    }

    @Test
    void findByNameResolvesSimpleNameWhenUnique() {
        assertThat(index.findByName("MockitoExtensionFieldsTest")).hasSize(1);
        assertThat(index.findByName(PKG + "MockitoExtensionFieldsTest")).hasSize(1);
        assertThat(index.findByName("NoSuchTestClass")).isEmpty();
    }

    @Test
    void reverseLookupsFindDeclaringClasses() {
        assertThat(index.realSubjectClasses("com.example.sample.beans.CheckoutService"))
                .contains(PKG + "MockitoExtensionFieldsTest");
        assertThat(index.mockedClasses("com.example.sample.beans.PaymentGateway"))
                .contains(PKG + "MockitoExtensionFieldsTest", PKG + "MockitoBeanFieldsTest",
                        PKG + "LegacyMockBeanFieldsTest");
        assertThat(index.staticMockedClasses("com.example.sample.beans.StripeGateway"))
                .contains(PKG + "MockStaticInMethodTest", PKG + "MockStaticInBeforeEachTest",
                        PKG + "MockStaticAsFieldTest");
    }

    @Test
    void unclassifiedContextTestsExcludesClassesThatMentionTheType() {
        List<String> unclassified = index.unclassifiedContextTests("com.example.sample.beans.PaymentGateway");

        // MockitoBeanFieldsTest/LegacyMockBeanFieldsTest ARE @SpringBootTest slices, but they
        // mention PaymentGateway directly, so they must not appear here.
        assertThat(unclassified).doesNotContain(PKG + "MockitoBeanFieldsTest", PKG + "LegacyMockBeanFieldsTest");
    }

    @Test
    void unclassifiedContextTestsIncludesContextSlicesThatNeverMentionTheType() {
        List<String> unclassified = index.unclassifiedContextTests("com.example.sample.beans.NotificationService");

        assertThat(unclassified).contains(
                PKG + "MockitoBeanFieldsTest", PKG + "LegacyMockBeanFieldsTest", PKG + "WebMvcSliceTest");
        // Not a context-slice test (@ExtendWith(MockitoExtension.class) only): must not appear.
        assertThat(unclassified).doesNotContain(PKG + "MockitoExtensionFieldsTest");
    }
}
