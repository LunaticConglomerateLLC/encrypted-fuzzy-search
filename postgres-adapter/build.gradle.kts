// PostgreSQL persistence and retrieval for protected search tokens.

dependencies {
    api(project(":search-core"))
    runtimeOnly("org.postgresql:postgresql:42.7.13")
}

tasks.register<JavaExec>("fuzzySearchBenchmark") {
    group = "verification"
    description = "Runs the deterministic synthetic PostgreSQL fuzzy-search benchmark."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    setExecutable("${System.getProperty("java.home")}/bin/java")
    mainClass.set(
        "io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.FuzzySearchBenchmark")
}
