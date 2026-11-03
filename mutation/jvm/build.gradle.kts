// JVM build of the KotlinX compiler sources for mutation testing with PIT.
// The compiler ships as Kotlin/Native, but everything except compiler/src/driver/Main.kt (posix I/O, clang driver)
// is plain common Kotlin, so it is compiled here for the JVM straight from ../../compiler/src (nothing copied or changed).
// Tests: GoldenTest runs the existing golden suite (compiler/tests/*.kt -> *.out) in-process: compileToIr on the JVM,
// then clang + run, exactly like compiler/test.sh. See ../README.md.
plugins {
    kotlin("jvm") version "2.4.21"
    id("info.solidsoft.pitest") version "1.19.0"
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

sourceSets {
    main { kotlin { setSrcDirs(listOf("../../compiler/src")); exclude("driver/Main.kt") } }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val repoRoot = file("../..").canonicalPath

tasks.test {
    useJUnitPlatform()
    systemProperty("kx.root", repoRoot)
    systemProperty("kx.baseline", layout.buildDirectory.file("ir-baseline.txt").get().asFile.path)
    systemProperty("kx.skip", System.getProperty("kx.skip") ?: "")
    systemProperty("kx.writeBaseline", System.getProperty("kx.writeBaseline") ?: "false")
    maxParallelForks = 1
}

pitest {
    pitestVersion.set("1.30.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("newkotlin.*"))
    targetTests.set(listOf("newkotlin.mutation.*"))
    excludedClasses.set(listOf("newkotlin.mutation.*"))
    // DEFAULTS = conditionals boundary, negate conditionals, math, increments, invert negatives, return values, void calls.
    mutators.set(listOf(System.getProperty("kx.pit.mutators") ?: "DEFAULTS"))
    threads.set((System.getProperty("kx.pit.threads") ?: "4").toInt())
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML", "CSV"))
    timeoutConstInMillis.set(20000)
    jvmArgs.set(listOf("-Dkx.skip=" + (System.getProperty("kx.skip") ?: ""), "-Dkx.root=$repoRoot", "-Dkx.baseline=" + layout.buildDirectory.file("ir-baseline.txt").get().asFile.path, "-Xss8m"))
    (System.getProperty("kx.pit.classes"))?.let { targetClasses.set(it.split(",")) }
    (System.getProperty("kx.pit.maxPerClass"))?.let { features.set(listOf("+CLASSLIMIT(limit[$it])")) }
}
