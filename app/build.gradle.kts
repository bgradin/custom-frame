plugins {
    id("com.google.devtools.ksp")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bgradin.customframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bgradin.customframe"
        minSdk = 25
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Opt in for frames already provisioned with this machine's debug signing identity.
    // Do not generate a new key for those frames: Android would reject the update.
    buildTypes {
        release {
            if (providers.gradleProperty("frameProvisionedSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

// Keep a flat, versioned copy of every assembled APK for distribution.
androidComponents {
    onVariants { variant ->
        val capitalizedName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val versionName = variant.outputs.first().versionName
        val exportApks = tasks.register<Copy>("export${capitalizedName}Apks") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            include("*.apk")
            into(rootProject.layout.buildDirectory.dir("distributions/custom-frame"))
            rename { filename ->
                "custom-frame-${versionName.get()}-${filename.removePrefix("app-")}"
            }
        }
        tasks.matching { it.name == "assemble$capitalizedName" }.configureEach {
            finalizedBy(exportApks)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.gridlayout:gridlayout:1.0.0")



    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")

    // WorkManager (periodic background sync)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Room (for recent cache)
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Glide (image loading + crossfade)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // OkHttp (Dropbox SDK transports)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SDK 7 includes Dropbox's 2026 API certificate and TLS updates.
    implementation(libs.dropbox.core.sdk)
}
