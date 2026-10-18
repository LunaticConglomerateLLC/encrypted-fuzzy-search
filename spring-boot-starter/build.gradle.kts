// Optional Spring Boot integration; framework support stays outside the core.

dependencies {
    implementation(project(":search-core"))
    implementation(project(":postgres-adapter"))
}
