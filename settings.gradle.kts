rootProject.name = "peashoot"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform 1.12 is aligned with Jetpack, so its runtime, lifecycle, and
        // saved-state artifacts are Google's and are published nowhere else. Narrowed to those
        // groups, so everything else still comes from Maven Central and only from there.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
            }
        }
    }
}

include("core", "proxy", "app")
