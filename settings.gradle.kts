rootProject.name = "Actions"
include("maven-core")

// This local-only plugin is excluded from version control.
if (file("maven-gradle-plugin").isDirectory) {
    include("maven-gradle-plugin")
}
