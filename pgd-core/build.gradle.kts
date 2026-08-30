plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.postgresql.jdbc)
    api(libs.kotlinx.serialization.json)
    implementation(platform(libs.zonky.embedded.postgres.bom))
    implementation(libs.zonky.embedded.postgres)
    implementation(libs.tomlj)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
