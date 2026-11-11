plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Debug server address default: Gradle property KIDTUBE_DEBUG_API_BASE_URL (e.g. your PC from a real phone), else the emulator host.
val debugApiBaseUrlRaw: String = (providers.gradleProperty("KIDTUBE_DEBUG_API_BASE_URL").orNull ?: "").trim().ifEmpty { "http://10.0.2.2:8001/" }
val debugApiBaseUrl: String = if (debugApiBaseUrlRaw.endsWith("/")) debugApiBaseUrlRaw else "$debugApiBaseUrlRaw/"

// ---- Release API base URL -------------------------------------------------------------------
// Never hard-coded in source: taken from the Gradle property KIDTUBE_API_BASE_URL.
// Release tasks fail (see verifyReleaseApiBaseUrl) while it is missing, CHANGE-ME or not https.
val releaseApiBaseUrlRaw: String = (providers.gradleProperty("KIDTUBE_API_BASE_URL").orNull ?: "").trim()
val releaseApiBaseUrl: String = if (releaseApiBaseUrlRaw.endsWith("/")) releaseApiBaseUrlRaw else "$releaseApiBaseUrlRaw/"

android {
    namespace = "com.bbro.kidtube"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bbro.kidtube"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // Emulator -> host machine. Cleartext to this host only, see src/debug/res/xml.
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT_API", "true") // http:// addresses are accepted in debug only
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT_API", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // java.time on minSdk 26
    }

    lint {
        abortOnError = true
    }

    testOptions {
        // Robolectric (in-memory Room tests) needs merged resources/manifest on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
        // Contract tests read the backend-generated fixtures at test time (never copied into the app).
        unitTests.all {
            val fixtures = rootProject.file("../backend/tests/contract_fixtures")
            it.systemProperty("contract.fixtures.dir", fixtures.absolutePath)
            it.inputs.dir(fixtures).withPropertyName("contractFixtures")
            // Committed Room schema JSON (1.json, 2.json) for the migration test.
            it.systemProperty("room.schemas.dir", "$projectDir/schemas")
            it.inputs.dir("$projectDir/schemas").withPropertyName("roomSchemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    // AppCompatDelegate.setApplicationLocales (per-app language; below API 33 it needs an AppCompatActivity).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Thumbnails (public image URLs only); the coloured placeholder is drawn underneath, so a failed load is harmless.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // HTTP body logging exists in debug builds only; src/release has a no-op stand-in.
    debugImplementation(libs.okhttp.logging.interceptor)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
}

// ---- Fail release builds early when the base URL is not configured ---------------------------
val verifyReleaseApiBaseUrl = tasks.register("verifyReleaseApiBaseUrl") {
    group = "verification"
    description = "Fails if KIDTUBE_API_BASE_URL is missing, CHANGE-ME or not an https URL."
    val raw = releaseApiBaseUrlRaw
    doLast {
        val problem = when {
            raw.isEmpty() -> "is not set"
            raw.contains("CHANGE-ME", ignoreCase = true) -> "is still CHANGE-ME"
            !raw.startsWith("https://") -> "must start with https:// (got '$raw')"
            else -> null
        }
        if (problem != null) {
            throw GradleException(
                "Release build blocked: Gradle property KIDTUBE_API_BASE_URL $problem.\n" +
                    "Set it to your backend URL, e.g. add this line to ~/.gradle/gradle.properties:\n" +
                    "    KIDTUBE_API_BASE_URL=https://kidtube.example.com/\n" +
                    "or pass -PKIDTUBE_API_BASE_URL=https://kidtube.example.com/ on the command line.",
            )
        }
    }
}

// Hooked onto the task that embeds the value, plus preReleaseBuild, so every release task
// (assembleRelease, bundleRelease, lintRelease, ...) hits the check.
tasks.configureEach {
    if (name == "generateReleaseBuildConfig" || name == "preReleaseBuild") {
        dependsOn(verifyReleaseApiBaseUrl)
    }
}
