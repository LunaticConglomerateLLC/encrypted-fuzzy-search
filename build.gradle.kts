plugins {
    base
}
version = "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java-library")

    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
    }
}
