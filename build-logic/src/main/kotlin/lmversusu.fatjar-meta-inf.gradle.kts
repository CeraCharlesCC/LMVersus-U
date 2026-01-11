import org.gradle.jvm.tasks.Jar
import java.util.zip.ZipFile

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
        val artifacts = configurations.getByName("runtimeClasspath")
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

tasks.withType<Jar>().configureEach {
    if (name == "shadowJar" || name == "buildFatJar") {
        exclude(metaInfDupPaths)

        dependsOn(collectRenamedMetaInf)
        from(renamedMetaInfDir)
    }
}
