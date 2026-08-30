plugins {
    alias(libs.plugins.kotlinJvm)
}

// Proves the generated code compiles and runs. src/main/kotlin here is written
// by `pgd generate`; GeneratedCodeTest fails if it has drifted from db/.
dependencies {
    implementation(libs.postgresql.jdbc)

    testImplementation(project(":pgd-core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
