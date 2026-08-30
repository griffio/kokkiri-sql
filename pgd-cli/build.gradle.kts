plugins {
    alias(libs.plugins.kotlinJvm)
    application
}


dependencies {
    implementation(project(":pgd-core"))
    // Optional: only loaded when the JVM is 22+ and libpg_query is installed.
    runtimeOnly(project(":pgd-native"))
    runtimeOnly(libs.logback)
}

application {
    applicationName = "pgd"
    mainClass.set("io.pgdescribe.cli.MainKt")
}
