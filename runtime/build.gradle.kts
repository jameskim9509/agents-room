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

// TODO(contract): once core/asyncapi.yaml has Java bindings (ADR 0005), generate them here with
// `npm run asyncapi:generate:java -- --out <build dir>` in a task and add that directory with
// sourceSets.main { java.srcDir(task) }. The gateway's temporary frame types then go away.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
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
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
