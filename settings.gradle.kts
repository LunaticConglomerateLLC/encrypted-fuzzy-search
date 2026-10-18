dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "encrypted-fuzzy-search"

include(
    "crypto-core",
    "language-core",
    "search-core",
    "postgres-adapter",
    "spring-boot-starter",
)
