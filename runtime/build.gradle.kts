import groovy.json.JsonOutput
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "agentsroom"
version = "0.0.1-SNAPSHOT"
description = "agents-room local runtime (Java/Spring, ADR 0002)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// The Java bindings of core/asyncapi.yaml (ADR 0005 decision 11) are generated at build time and never
// committed. The task runs the command behind `npm run asyncapi:generate:java` itself, as
// `node --import tsx scripts/generate-messages.ts --java ...` from the repo root: without npm and
// cmd.exe in between, the argv and its quoting are the same on Windows and Linux. It needs node on the
// PATH and `npm ci` at the repo root.
val repoRoot = layout.projectDirectory.dir("..")
val contractPackage = "agentsroom.contract"

val generateContractBindings = tasks.register<Exec>("generateContractBindings") {
    group = "build"
    description = "Generates the Java bindings of core/asyncapi.yaml into build/generated/sources/contract."
    val outputDir = layout.buildDirectory.dir("generated/sources/contract")
    val tsx = repoRoot.dir("node_modules/tsx").asFile
    inputs.file(repoRoot.file("core/asyncapi.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.file("scripts/generate-messages.ts")).withPathSensitivity(PathSensitivity.RELATIVE)
    // The locked Modelina version shapes the output too.
    inputs.file(repoRoot.file("package-lock.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("package", contractPackage)
    outputs.dir(outputDir)
    workingDir(repoRoot)
    commandLine(
        "node", "--import", "tsx", "scripts/generate-messages.ts", "--java",
        "--out", outputDir.get().asFile.absolutePath, "--package", contractPackage,
    )
    doFirst {
        if (!tsx.isDirectory) {
            throw GradleException("node_modules/tsx is missing: run `npm ci` at the repo root first.")
        }
    }
}

// The generated classes compile in their own source set, so that the handwritten code keeps every lint
// warning an error while Modelina's style (redundant casts, a doc comment after the annotations) does not
// fail the build.
val contract = sourceSets.create("contract") {
    java.srcDir(generateContractBindings)
}

// The official ACP Java SDK, pinned exactly (ADR 0003 decision 2). Jackson 3 and Reactor come in at the
// versions Spring Boot manages, which the SDK accepts (its floors are Jackson 3.0 and Reactor 3.7).
val acpSdkVersion = "0.80.0"

dependencies {
    "contractImplementation"("com.fasterxml.jackson.core:jackson-annotations")
    implementation(contract.output)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.agentclientprotocol:acp-core:$acpSdkVersion")
    implementation("com.agentclientprotocol:acp-json-jackson3:$acpSdkVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<JavaCompile>(contract.compileJavaTaskName) {
    options.compilerArgs.add("-Xlint:-cast,-dangling-doc-comments")
}

// A stable file name: the desktop shell finds the jar through build/dev-runtime.json.
// Native access lets the runtime call kernel32 for its job object (process.WindowsJobObject) without
// the JVM's restricted-method warning; the shell's java command line stays as the handshake fixes it.
tasks.bootJar {
    archiveFileName = "agents-room-runtime.jar"
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// No "-plain.jar" next to the boot jar.
tasks.jar {
    enabled = false
}

// build/dev-runtime.json tells the desktop shell (dev mode) which JDK 25 java executable and which jar
// to launch. The shell never runs Gradle itself, because Gradle output would pollute the runtime's stdout.
val javaLauncherPath = javaToolchains.launcherFor(java.toolchain)
    .map { it.executablePath.asFile.absolutePath }
val bootJarPath = tasks.bootJar.flatMap { it.archiveFile }.map { it.asFile.absolutePath }

val writeDevRuntimeDescriptor = tasks.register("writeDevRuntimeDescriptor") {
    group = "build"
    description = "Writes build/dev-runtime.json with the JDK 25 java executable and the boot jar path."
    val java = javaLauncherPath
    val jar = bootJarPath
    val descriptor = layout.buildDirectory.file("dev-runtime.json")
    inputs.property("java", java)
    inputs.property("jar", jar)
    outputs.file(descriptor)
    doLast {
        val json = JsonOutput.toJson(linkedMapOf("java" to java.get(), "jar" to jar.get()))
        descriptor.get().asFile.writeText(JsonOutput.prettyPrint(json) + "\n")
    }
}

tasks.bootJar {
    finalizedBy(writeDevRuntimeDescriptor)
}

tasks.assemble {
    dependsOn(writeDevRuntimeDescriptor)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // RuntimeJarTests launch the real boot jar to check the stdout/stdin handshake end to end.
    val jar = bootJarPath
    inputs.file(tasks.bootJar.flatMap { it.archiveFile })
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dagentsroom.runtime.bootJar=${jar.get()}") })
    // The contract tests read core/fixtures and validate frames against core/asyncapi.yaml with
    // scripts/check-contract-fixtures.mjs (node), the validator of `npm run contract:check`. Its verdicts
    // also depend on the locked ajv and @asyncapi/parser and on the node version CI installs (.nvmrc),
    // so a bump of either reruns the tests instead of reusing a cached result.
    val root = repoRoot.asFile.absolutePath
    inputs.dir(repoRoot.dir("core/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.file("core/asyncapi.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.file("scripts/check-contract-fixtures.mjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.file("package-lock.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(repoRoot.file(".nvmrc")).withPathSensitivity(PathSensitivity.RELATIVE)
    // The integration tests run the mock ACP agent as the Codex adapter (node, process boundary).
    inputs.file(repoRoot.file("e2e/desktop/fixtures/mock-acp-agent.cjs")).withPathSensitivity(PathSensitivity.RELATIVE)
    // No test starts an adapter a developer's shell names, or the installed codex-acp (D11): the test JVM
    // fails closed like an e2e run, and blank values hide the shell's adapter variables (the launcher
    // takes blank as unset). A test that runs an adapter names the mock as a Spring property, which wins
    // over the environment. Should anything still reach Codex, it gets a scratch CODEX_HOME, never ~/.codex.
    environment("AGENTS_ROOM_E2E", "1")
    environment("AGENTS_ROOM_HARNESS_CODEX_COMMAND", "")
    environment("AGENTS_ROOM_ADAPTERS_DIR", "")
    environment("AGENTS_ROOM_NODE", "")
    environment("AGENTS_ROOM_NODE_ELECTRON", "")
    environment("CODEX_HOME", layout.buildDirectory.dir("tmp/test-codex-home").get().asFile.absolutePath)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dagentsroom.repoRoot=$root") })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
