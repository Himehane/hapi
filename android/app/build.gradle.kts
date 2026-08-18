import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // New-session form draft + prefs persist as JSON blobs in DataStore (B-M3d).
    alias(libs.plugins.kotlin.serialization)
    // NOTE: com.google.gms.google-services is deliberately NOT applied in M0.
    // It is added in M4a together with google-services.json + the FCM service,
    // so the scaffold builds green without any Firebase project configured.
}

android {
    namespace = "app.hapi.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "run.hapi.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
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

    buildFeatures {
        compose = true
        // About screen surfaces BuildConfig.VERSION_NAME (B-M4e).
        buildConfig = true
    }

    testOptions {
        unitTests.all { test ->
            // Chat pipeline smoke tests replay golden fixtures from the repo
            // root (same wiring as :core:protocol / :core:data).
            test.systemProperty(
                "hapi.fixtures.dir",
                rootDir.parentFile.resolve("shared/fixtures").absolutePath,
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // QR pairing (ScanContract activity-result API; no Play Services).
    implementation(libs.zxing.android.embedded)

    // Generated images (chat, B-M2d2): loader wired in HubGraph over the
    // authed + disk-cached hub image client.
    implementation(libs.coil.compose)

    // Markdown rendering (B-M2d1). commonmark comes through :core:protocol's
    // `api` too; declared here because ui/markdown walks the AST types directly.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.highlights)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM unit tests (ViewModel combine logic with fake stores).
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
