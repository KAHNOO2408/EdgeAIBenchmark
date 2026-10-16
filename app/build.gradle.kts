plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aspar.yolodetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aspar.yolodetector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // فایل مدل نباید فشرده شود تا بتوان آن را memory-map کرد
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // lifecycleScope / repeatOnLifecycle برای دریافت آمار از سرویس
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // TensorFlow Lite — Interpreter خام + GPU Delegate
    val tflite = "2.16.1"
    implementation("org.tensorflow:tensorflow-lite:$tflite")
    implementation("org.tensorflow:tensorflow-lite-gpu:$tflite")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:$tflite")
}
