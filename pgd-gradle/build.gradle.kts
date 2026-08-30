plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":pgd-core"))
    runtimeOnly(project(":pgd-native"))

    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("pgd") {
            id = "io.pgdescribe"
            implementationClass = "io.pgdescribe.gradle.PgdPlugin"
            displayName = "pgd"
            description = "Verify PostgreSQL queries against your migrations and generate Kotlin for them."
        }
    }
}

tasks.test {
    useJUnit()
    // TestKit runs real builds; give them somewhere predictable to cache.
    systemProperty("org.gradle.testkit.dir", layout.buildDirectory.dir("testkit").get().asFile.path)
}
