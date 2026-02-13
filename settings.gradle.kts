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
        val bubbMavenUser = providers.gradleProperty("BUBBL_MAVEN_USER").orNull
            ?: System.getenv("BUBBL_MAVEN_USER")
        val bubbMavenToken = providers.gradleProperty("BUBBL_MAVEN_TOKEN").orNull
            ?: System.getenv("BUBBL_MAVEN_TOKEN")

        if (!bubbMavenUser.isNullOrBlank() && !bubbMavenToken.isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/bubbl-repo/bubbl-android-sdk")
                credentials {
                    username = bubbMavenUser
                    password = bubbMavenToken
                }
            }
        }
    }
}

rootProject.name = "My Bubbl Host"
include(":app")
 
