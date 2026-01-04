plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dependency.license.report)
    application
}

val projectGroup: String by project
val projectVersion: String by project

group = projectGroup
version = projectVersion

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)

    implementation(libs.caffeine)

    implementation(libs.openai.java)
    implementation(libs.ktoml.core)

    implementation(platform(libs.ktor.bom))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.flaxoos.ktor.server.rate.limiting)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dlmversusu.configDir=${projectDir}")
}

tasks.register<JavaExec>("testRun") {
    //dependsOn("build")
    group = "application"
    description = "Runs the Ktor server as a manual integration test."

    mainClass.set("io.ktor.server.netty.EngineMain")
    classpath = sourceSets["main"].runtimeClasspath

    workingDir = projectDir
    jvmArgs("-Dlmversusu.configDir=${projectDir}")
}

licenseReport {
    renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
        com.github.jk1.license.render.TextReportRenderer("THIRD-PARTY-LICENSES")
    )
}

tasks.withType<Jar>().configureEach {
    if (name == "buildFatJar" || name == "shadowJar") {
        dependsOn("generateLicenseReport")
        from(layout.buildDirectory.dir("reports/dependency-license")) {
            into("META-INF")
        }
    }
}

