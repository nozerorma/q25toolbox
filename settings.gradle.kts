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
        maven { url = uri("https://jitpack.io") } // required for libsu
        maven { url = uri("https://api.xposed.info/") } // required for the Xposed API stub
    }
}

rootProject.name = "Q25Toolbox"
include(":app")
