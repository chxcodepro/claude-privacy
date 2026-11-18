pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://archiva.interlocsolutions.com/archiva/repository/internal/") {
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}
rootProject.name = "ClaudePrivacyHook"
include(":app")
