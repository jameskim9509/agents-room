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

dependencies {
    "contractImplementation"("com.fasterxml.jackson.core:jackson-annotations")
    implementation(contract.output)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
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
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dagentsroom.repoRoot=$root") })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
