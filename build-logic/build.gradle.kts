plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.plugin.node.gradle)
    implementation(libs.plugin.dependency.license.report)
}
