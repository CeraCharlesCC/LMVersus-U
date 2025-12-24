rootProject.name = "LMVersus-U"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://packages.confluent.io/maven/")
    }
}
