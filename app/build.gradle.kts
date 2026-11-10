plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.spectradesk.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spectradesk.camera"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The phone is arm64; keeping both ABIs costs nothing here because the
        // app has no native libraries of its own.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // Unsigned-by-default: a self-signed release build would be rejected
            // by the store later and confuses side-loading, so debug signing is
            // used for the deliverable and noted in the README.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The project lives under a path containing non-ASCII characters.
            // Gradle's test worker passes the classpath on the command line, and
            // on Windows the resulting command can exceed the limit or be
            // mangled by the console code page - which surfaces as
            // "ClassNotFoundException" for classes that are demonstrably present.
            // Writing the classpath to a file removes the dependency on the
            // command-line length and encoding entirely.
            it.jvmArgs(
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
            )
            it.systemProperty("file.encoding", "UTF-8")
            it.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CameraX: the only Android camera API with a stable still-capture plus
    // preview path across vendors.  Camera2 would give RAW and per-frame
    // exposure control, but on this device CameraX's EXTENSION_* and manual
    // white-balance hooks are enough, and it removes a large class of
    // device-specific bugs.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
