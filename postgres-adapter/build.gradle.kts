// PostgreSQL persistence and retrieval for protected search tokens.

dependencies {
    api(project(":search-core"))
    runtimeOnly("org.postgresql:postgresql:42.7.13")
}

val databaseFunctionalTestClasses = listOf(
    "io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresEncryptedFuzzySearchFunctionalTest",
    "io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresTokenIndexRepositoryTest",
)

val functionalTest = tasks.register<Test>("functionalTest") {
    group = "verification"
    description = "Runs PostgreSQL-backed functional tests against the disposable Compose database."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        databaseFunctionalTestClasses.forEach { includeTestsMatching(it) }
    }
}

tasks.named<Test>("test") {
    exclude("**/PostgresEncryptedFuzzySearchFunctionalTest.class")
    exclude("**/PostgresTokenIndexRepositoryTest.class")
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    executionData(functionalTest.get())
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(functionalTest)
    executionData(functionalTest.get())
}

tasks.named("check") {
    dependsOn(functionalTest)
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

tasks.register<JavaExec>("postgresLoadBenchmark") {
    group = "verification"
    description = "Runs concurrent synthetic plaintext and EFS search loads against Compose PostgreSQL."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    setExecutable("${System.getProperty("java.home")}/bin/java")
    mainClass.set(
        "io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres.PostgresLoadBenchmark")
}
