plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Need these plugins on the classpath so our convention plugins can apply them
    implementation(libs.plugin.node.gradle)
    implementation(libs.plugin.dependency.license.report)
}
