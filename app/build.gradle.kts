plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val phoneAiOpenCl = System.getenv("PHONEAI_OPENCL") == "1" ||
    providers.gradleProperty("phoneaiOpenCl").orNull == "true"

android {
    namespace = "com.phoneai.core"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneai.core"
        minSdk = 33
        targetSdk = 36
        versionCode = 19
        versionName = "0.19.0"
        buildConfigField("boolean", "OPENCL_BUILD", phoneAiOpenCl.toString())
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libggml.so",
                "**/libggml-base.so",
                "**/libggml-cpu.so"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    if (project.findProject(":llamaLib") != null) {
        implementation(project(":llamaLib"))
    } else {
        throw GradleException(
            "llama.cpp is missing. Run scripts/setup_llama.sh (or setup_llama.ps1) first."
        )
    }

    if (project.findProject(":whisperLib") != null) {
        implementation(project(":whisperLib"))
    } else {
        throw GradleException(
            "whisper.cpp is missing. Run scripts/setup_whisper.sh (or setup_whisper.ps1) first."
        )
    }

    val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
    if (sherpaAar.exists()) {
        implementation(files(sherpaAar))
    } else {
        throw GradleException(
            "sherpa-onnx AAR is missing. Run scripts/setup_sherpa_onnx.sh (or setup_sherpa_onnx.ps1) first."
        )
    }
}
