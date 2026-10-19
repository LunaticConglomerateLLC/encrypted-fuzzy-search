plugins {
    base
    jacoco
    id("com.diffplug.spotless") version "8.10.4"
    id("org.owasp.dependencycheck") version "13.0.0"
    id("org.cyclonedx.bom") version "3.5.1"
}
version = "0.1.0" // x-release-please-version

spotless {
    java {
        target("**/src/**/*.java")
        googleJavaFormat("1.30.0")
    }
    format("misc") {
        target("*.gradle.kts", "*.md", ".github/**/*.yml", ".github/**/*.yaml")
        targetExclude("build/**", "**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0F
    nvd {
        apiKey = providers.gradleProperty("nvdApiKey").orNull ?: System.getenv("NVD_API_KEY") ?: ""
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    dependencies {
        "testImplementation"("org.testng:testng:7.12.0")
    }

    tasks.withType<Test>().configureEach {
        useTestNG()
        testLogging {
            events("failed", "skipped")
        }
        finalizedBy("jacocoTestReport")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true
            html.required = true
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("test"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.90".toBigDecimal()
                }
            }
        }
        onlyIf {
            project.extensions.getByType<SourceSetContainer>()
                .getByName("main").allSource.files.any { it.isFile }
        }
    }

    tasks.named("check") {
        dependsOn("jacocoTestCoverageVerification")
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") })
}
