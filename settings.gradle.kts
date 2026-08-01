plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html
buildCache {
    local {
        isEnabled = true
    }
}
rootProject.name = "paperclip-jules-bridge"