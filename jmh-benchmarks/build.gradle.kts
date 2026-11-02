// Isolated JMH source set: benchmark harness dependencies are not part of production artifacts.
val jmh = sourceSets.create("jmh")

configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    add(jmh.implementationConfigurationName, project(":crypto-core"))
    add(jmh.implementationConfigurationName, project(":language-core"))
    add(jmh.implementationConfigurationName, project(":search-core"))
    add(jmh.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmh.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the isolated JVM microbenchmarks; customize with --args."
    dependsOn(tasks.named(jmh.classesTaskName))
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args = listOf("-rf", "json", "-rff", "build/reports/jmh/results.json")
    doFirst {
        layout.buildDirectory.dir("reports/jmh").get().asFile.mkdirs()
        val sourceRevision =
            providers.exec {
                commandLine("git", "rev-parse", "HEAD")
            }.standardOutput.asText.get().trim()
        val sourceTreeDirty =
            providers.exec {
                commandLine("git", "status", "--porcelain", "--untracked-files=no")
            }.standardOutput.asText.get().trim().isNotEmpty()
        args("-p", "sourceRevision=${sourceRevision.ifEmpty { "unknown" }}")
        args("-p", "sourceTreeDirty=$sourceTreeDirty")
    }
}
