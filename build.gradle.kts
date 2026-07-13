import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.subnocte"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.8"

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("com.github.javaparser:javaparser-core:3.28.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// This server talks JSON-RPC over stdout when run with stdio transport;
// an executable jar with a repackaged main class keeps that entry point simple.
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("spring-wiring-mcp.jar")
}
