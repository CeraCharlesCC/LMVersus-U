import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.github.jk1.dependency-license-report")
}

licenseReport {
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("THIRD-PARTY-LICENSES")
    )
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("LICENSE")) {
        into("META-INF/LICENSES")
    }
    from(rootProject.file("AGPL-3.0.txt")) {
        into("META-INF/LICENSES")
    }
}

tasks.withType<Jar>().configureEach {
    if (name == "shadowJar" || name == "buildFatJar") {
        dependsOn("generateLicenseReport")
        from(layout.buildDirectory.dir("reports/dependency-license")) {
            into("META-INF")
        }
    }
}
