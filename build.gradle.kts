plugins {
	kotlin("jvm") version "2.1.10"
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("com.google.code.gson:gson:2.12.1")
	implementation(project(":maven-core"))
}

application {
	mainClass.set("MainKt")
}

kotlin { jvmToolchain(21) }
tasks.test { dependsOn(subprojects.map { "${it.path}:test" }) }

tasks.register<JavaExec>("manageMaven") {
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass.set("maven.MavenRepositoryKt")
}
