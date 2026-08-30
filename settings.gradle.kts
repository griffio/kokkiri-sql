rootProject.name = "pgdescribe"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":pgd-core")
include(":pgd-cli")
include(":pgd-gradle")
include(":pgd-native")
include(":example")
