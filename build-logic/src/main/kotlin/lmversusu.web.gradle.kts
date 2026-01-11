import com.github.gradle.node.npm.task.NpxTask
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets

plugins {
    id("com.github.node-gradle.node")
}

node {
    download.set(true)
    version.set("20.11.1")
}

val webSrcDir = layout.projectDirectory.dir("src/main/resources/web")
val webDistDir = layout.buildDirectory.dir("generated/web-dist")
val esbuildMeta = webDistDir.map { it.file("meta.json") }

val appEntryRel = "src/main/resources/web/app.js"
val cssEntryRel = "src/main/resources/web/styles.css"

val copyWebStatic = tasks.register<Copy>("copyWebStatic") {
    from(webSrcDir)
    into(webDistDir)

    exclude("app/**")
    exclude("app.js", "styles.css", "index.html")
}

val esbuildBundle = tasks.register<NpxTask>("esbuildBundle") {
    dependsOn(tasks.named("npmInstall"), copyWebStatic)

    workingDir.set(layout.projectDirectory)

    command.set("esbuild")
    args.set(
        webDistDir.zip(esbuildMeta) { distDir, metaFile ->
            listOf(
                appEntryRel,
                cssEntryRel,

                "--bundle",
                "--minify",
                "--sourcemap",

                "--platform=browser",
                "--format=esm",
                "--splitting",

                "--entry-names=[name]-[hash]",
                "--chunk-names=chunks/[name]-[hash]",

                "--external:/i18n/*",

                "--outdir=${distDir.asFile.absolutePath}",
                "--metafile=${metaFile.asFile.absolutePath}",
            )
        }
    )

    inputs.file(layout.projectDirectory.file(appEntryRel))
    inputs.file(layout.projectDirectory.file(cssEntryRel))
    inputs.dir(webSrcDir.dir("app"))
    outputs.dir(webDistDir)
}

fun normalizePathForCompare(s: String): String =
    s.replace('\\', '/')
        .removePrefix("./")
        .lowercase()

fun findOutputBasenameForEntryPoint(metaFile: java.io.File, entryPointRel: String): String {
    val meta = JsonSlurper().parse(metaFile) as Map<*, *>
    val outputs = meta["outputs"] as? Map<*, *> ?: error("metafile has no 'outputs'")

    val entryRelNorm = normalizePathForCompare(entryPointRel)
    val entryFileName = entryRelNorm.substringAfterLast('/')

    val match = outputs.entries.firstOrNull { (_, vAny) ->
        val v = vAny as? Map<*, *> ?: return@firstOrNull false
        val ep = v["entryPoint"]?.toString() ?: return@firstOrNull false
        val epNorm = normalizePathForCompare(ep)

        epNorm == entryRelNorm || epNorm.endsWith("/$entryFileName")
    }

    if (match == null) {
        val entryPointsSeen = outputs.values
            .mapNotNull { (it as? Map<*, *>)?.get("entryPoint")?.toString() }
            .distinct()
            .joinToString("\n") { "  - $it" }

        error(
            "Could not find output for entryPoint=$entryPointRel in metafile.\n" +
                    "Entry points in metafile were:\n$entryPointsSeen\n" +
                    "Tip: ensure esbuildBundle.workingDir is project root and you pass relative entrypoints."
        )
    }

    val outPath = match.key.toString().replace('\\', '/')
    return outPath.substringAfterLast('/')
}

val rewriteIndexHtml = tasks.register("rewriteIndexHtml") {
    dependsOn(esbuildBundle)

    inputs.file(webSrcDir.file("index.html"))
    inputs.file(esbuildMeta)
    outputs.file(webDistDir.map { it.file("index.html") })

    doLast {
        val metaFile = esbuildMeta.get().asFile

        val appOut = findOutputBasenameForEntryPoint(metaFile, appEntryRel)
        val cssOut = findOutputBasenameForEntryPoint(metaFile, cssEntryRel)

        val srcHtml = webSrcDir.file("index.html").asFile.readText(StandardCharsets.UTF_8)

        val outHtml = srcHtml
            .replace("""href="./styles.css"""", """href="./$cssOut"""")
            .replace("""src="./app.js"""", """src="./$appOut"""")

        val outFile = webDistDir.get().file("index.html").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(outHtml, StandardCharsets.UTF_8)
    }
}

val buildWeb = tasks.register("buildWeb") {
    dependsOn(copyWebStatic, esbuildBundle, rewriteIndexHtml)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildWeb)

    exclude("web/**")

    from(webDistDir) {
        into("web")
        exclude("meta.json")
        exclude("**/*.map")
    }
}
