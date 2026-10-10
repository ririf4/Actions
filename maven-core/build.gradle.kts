plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "dev.swiftstorm"
version = "0.1.0"

repositories { mavenCentral() }
dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
java { withSourcesJar() }
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        artifactId = "swiftstorm-maven-core"
    }
}
