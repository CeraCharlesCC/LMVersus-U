import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import com.github.gradle.node.npm.task.NpxTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.jvm.tasks.Jar
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dependency.license.report)
    alias(libs.plugins.node.gradle)
    application
}

val projectGroup: String by project
val projectVersion: String by project

group = projectGroup
version = projectVersion

repositories {
    mavenCentral()
}

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
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.netty)

    implementation(platform(libs.log4j.bom))

    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)

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
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("THIRD-PARTY-LICENSES")
    )
}

node {
    download.set(true)
    version.set("20.11.1")
}

val metaInfDupNames = listOf("NOTICE", "NOTICE.md", "NOTICE.txt", "LICENSE", "LICENSE.md", "LICENSE.txt")
val metaInfDupPaths = metaInfDupNames.map { "META-INF/$it" }

fun String.safeStem(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

val renamedMetaInfDir = layout.buildDirectory.dir("generated/renamed-meta-inf")

val collectRenamedMetaInf = tasks.register("collectRenamedMetaInf") {
    outputs.dir(renamedMetaInfDir)

    doLast {
        val outRoot = renamedMetaInfDir.get().asFile
        outRoot.deleteRecursively()
        outRoot.mkdirs()

        val artifactType = Attribute.of("artifactType", String::class.java)
        val artifacts = configurations.runtimeClasspath.get()
            .incoming
            .artifactView { attributes.attribute(artifactType, "jar") }
            .artifacts
            .artifacts

        artifacts.forEach { ra ->
            val jarFile = ra.file
            val cid = ra.id.componentIdentifier

            val libName = when (cid) {
                is ModuleComponentIdentifier -> "${cid.group}-${cid.module}-${cid.version}"
                else -> jarFile.nameWithoutExtension
            }.safeStem()

            ZipFile(jarFile).use { zip ->
                metaInfDupNames.forEach { baseName ->
                    val entry = zip.getEntry("META-INF/$baseName") ?: return@forEach

                    val outFile = outRoot.resolve("META-INF/${libName}-$baseName")
                    outFile.parentFile.mkdirs()

                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}

val webSrcDir = layout.projectDirectory.dir("src/main/resources/web")
val webDistDir = layout.buildDirectory.dir("generated/web-dist")

val copyWeb = tasks.register<Copy>("copyWeb") {
    from(webSrcDir)
    into(webDistDir)
    exclude("app/**") // module sources (they get bundled)
    // keep app.js and styles.css out too; we'll overwrite them with minified versions
    exclude("app.js", "styles.css")
}

val esbuildJs = tasks.register<NpxTask>("esbuildJs") {
    dependsOn(tasks.npmInstall, copyWeb)
    command.set("esbuild")
    args.set(
        listOf(
            "${webSrcDir.file("app.js").asFile.absolutePath}",
            "--bundle",
            "--format=esm",
            "--platform=browser",
            "--minify",
            "--sourcemap",
            "--outfile=${webDistDir.get().file("app.js").asFile.absolutePath}",
        )
    )
    inputs.file(webSrcDir.file("app.js"))
    inputs.dir(webSrcDir.dir("app"))
    outputs.file(webDistDir.map { it.file("app.js") })
}

val esbuildCss = tasks.register<NpxTask>("esbuildCss") {
    dependsOn(tasks.npmInstall, copyWeb)
    command.set("esbuild")
    args.set(
        listOf(
            "${webSrcDir.file("styles.css").asFile.absolutePath}",
            "--minify",
            "--sourcemap",
            "--outfile=${webDistDir.get().file("styles.css").asFile.absolutePath}",
        )
    )
    inputs.file(webSrcDir.file("styles.css"))
    outputs.file(webDistDir.map { it.file("styles.css") })
}

val buildWeb = tasks.register("buildWeb") {
    dependsOn(copyWeb, esbuildJs, esbuildCss)
}

tasks.processResources {
    dependsOn(buildWeb)

    exclude("web/**")

    from(webDistDir) {
        into("web")
    }

    from(rootProject.file("LICENSE")) {
        into("META-INF/LICENSES")
    }

    from(rootProject.file("AGPL-3.0.txt")) {
        into("META-INF/LICENSES")
    }
}

tasks.withType<Jar>().configureEach {
    if (name == "buildFatJar" || name == "shadowJar") {
        exclude(metaInfDupPaths)

        dependsOn(collectRenamedMetaInf)
        from(renamedMetaInfDir)

        dependsOn("generateLicenseReport")
        from(layout.buildDirectory.dir("reports/dependency-license")) {
            into("META-INF")
        }
    }
}
