import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.gradle.jvm.tasks.Jar

/**
 * Convention plugin: License reporting and embedding
 * Configures the dependency-license-report plugin and copies project licenses to META-INF.
 */

plugins {
    id("com.github.jk1.dependency-license-report")
}

licenseReport {
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("THIRD-PARTY-LICENSES")
    )
}

// Copy root LICENSE files into processed resources
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("LICENSE")) {
        into("META-INF/LICENSES")
    }
    from(rootProject.file("AGPL-3.0.txt")) {
        into("META-INF/LICENSES")
    }
}

// Add license report to fat jars
tasks.withType<Jar>().configureEach {
    if (name == "shadowJar" || name == "buildFatJar") {
        dependsOn("generateLicenseReport")
        from(layout.buildDirectory.dir("reports/dependency-license")) {
            into("META-INF")
        }
    }
}
