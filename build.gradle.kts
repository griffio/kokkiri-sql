import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Built with a modern JDK so the FFM work in M6 has somewhere to land,
        // but emitting 17 bytecode so `pgd` runs on whatever JDK a project has.
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
    }
}
