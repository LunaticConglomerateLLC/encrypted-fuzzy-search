// Optional Spring Boot integration; framework support stays outside the core.

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":search-core"))
    api(project(":postgres-adapter"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.1.1")

    testImplementation("org.springframework.boot:spring-boot-test:4.1.1")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
}
