pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localBubblSdkMavenPath = providers.gradleProperty("BUBBL_ANDROID_SDK_LOCAL_MAVEN").orNull
    ?: System.getenv("BUBBL_ANDROID_SDK_LOCAL_MAVEN")
    ?: "../../bubbl-current/sdk/bubbl-android-sdk-standalone/sdk/build/localMaven"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        val localBubblSdkMavenDir = file(localBubblSdkMavenPath)
        if (localBubblSdkMavenDir.exists()) {
            maven {
                url = uri(localBubblSdkMavenDir)
            }
        }
        maven {
            url = uri("https://maven.bubbl.tech/repository/releases/")
        }
    }
}

rootProject.name = "My Bubbl Host"
include(":app")
 
