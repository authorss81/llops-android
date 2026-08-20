package com.authorss81.noteflow

/**
 * Single source of truth for the R2-b2b2-DEP-04 mavenCentral() allow-list, shared
 * by the source-pinning tests (phase-146 + review): `B2Deps03DependencyVerificationTest`
 * and `Phase146BuildIntegrityTest` must agree on the exact group set so a drift in
 * one list cannot silently pass the weaker test.
 *
 * Keep in sync with the literal `includeGroupByRegex("...")` lines in
 * `dependencyResolutionManagement` (settings.gradle.kts) and with
 * `mavenCentralAllowList` (plugin resolution) — adding a library to the build means
 * updating all three in the same commit.
 */
internal object CentralAllowlist {

    val groups: List<String> = listOf(
        "org\\.jetbrains.*",
        "io\\.coil.*",
        "com\\.squareup.*",
        "org\\.commonmark.*",
        "org\\.jsoup.*",
        "net\\.zetetic.*",
        "com\\.github\\.pemistahl",
        "dev\\.snipme.*",
        "junit",
        "org\\.hamcrest.*",
        "org\\.junit.*",
        "org\\.jspecify.*",
        "org\\.checkerframework.*",
        "com\\.google\\.errorprone.*",
        "com\\.google\\.j2objc.*",
        "com\\.google\\.code.*",
        "org\\.tensorflow.*",
        "com\\.google\\.protobuf.*",
        "com\\.google\\.flatbuffers.*",
        "io\\.grpc.*",
        "io\\.netty.*",
        "io\\.perfmark.*",
        "org\\.xerial.*",
        "com\\.google\\.guava.*",
        "com\\.google\\.crypto.*",
        "com\\.google\\.dagger.*",
        "com\\.google\\.jimfs.*",
        "com\\.google\\.auto.*",
        "com\\.google\\.api.*",
        "com\\.google\\.accompanist.*",
        "com\\.google\\.devtools.*",
        "com\\.google\\.android",
        "com\\.googlecode.*",
        "com\\.intellij.*",
        "com\\.sun.*",
        "jakarta.*",
        "javax.*",
        "commons-.*",
        "org\\.apache.*",
        "org\\.bouncycastle.*",
        "org\\.codehaus.*",
        "org\\.ow2.*",
        "org\\.jdom.*",
        "org\\.eclipse.*",
        "org\\.glassfish.*",
        "org\\.jvnet.*",
        "org\\.sonatype.*",
        "org\\.bitbucket.*",
        "org\\.slf4j.*",
        "it\\.unimi\\.dsi.*",
        "net\\.java.*",
        "net\\.sf.*",
        // Phase 195: Paparazzi screenshot-render suite (test-only) transitive
        // groups that resolve from Maven Central on the unit-test classpath.
        "app\\.cash\\.paparazzi.*",
        "net\\.bytebuddy.*",
        "kxml2",
        "dev\\.drewhamilton\\.poko.*"
    )
}
