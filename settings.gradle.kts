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
    }
}

rootProject.name = "PhoneAICore"
include(":app")

val llamaDir = file("third_party/llama.cpp/examples/llama.android/lib")
if (llamaDir.exists()) {
    include(":llamaLib")
    project(":llamaLib").projectDir = llamaDir
}

val whisperDir = file("third_party/whisper.cpp/examples/whisper.android/lib")
if (whisperDir.exists()) {
    include(":whisperLib")
    project(":whisperLib").projectDir = whisperDir
}
