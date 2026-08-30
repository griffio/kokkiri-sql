import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinJvm)
}

// The Foreign Function & Memory API was finalised in Java 22, but this module
// has to be placeable on a Java 17 classpath like every other. So the service
// entry point stays at 17 and only the FFM implementation is compiled for 22;
// it is reached by name, so an older JVM simply never loads it.
val ffm: SourceSet by sourceSets.creating

dependencies {
    implementation(project(":pgd-core"))
    "ffmCompileOnly"(project(":pgd-core"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.named<KotlinCompile>("compileFfmKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
        freeCompilerArgs.set(listOf("-Xjdk-release=22"))
    }
}

// Must match the Kotlin target, or Gradle rejects the source set as inconsistent.
tasks.named<JavaCompile>("compileFfmJava") { options.release.set(22) }

tasks.named<Jar>("jar") {
    from(ffm.output)
}

tasks.test {
    useJUnit()
    classpath += ffm.output
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
