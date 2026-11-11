// androidApp — the native Android front-end (Jetpack Compose / Material 3) over the unchanged
// `:shared` domain + data module. Presentation only: every behaviour resolves to a shared rule.
//
// Toolchain contract (plan §4.4 / TASK-03):
//  - AGP 9 has built-in Kotlin, so the legacy JetBrains Android Kotlin plugin is NEVER applied
//    (it is incompatible with the AGP-9 DSL and applying it breaks the build).
//  - `org.jetbrains.kotlin.plugin.compose` is the only additional plugin: Compose needs the
//    compiler plugin, and on AGP 9 that is the whole Kotlin/Compose wiring.
//  - Every dependency version comes from `gradle/libs.versions.toml`; the Compose artifacts are
//    version-less in the catalog and therefore REQUIRE `platform(libs.compose.bom)`.
//  - Repositories live in `settings.gradle.kts` only (never here).
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// ---------------------------------------------------------------------------
// Release identity and release signing are environment-driven; the tree carries
// neither a version number to bump per release nor any secret material.
//
// ANDROID_VERSION_CODE / ANDROID_VERSION_NAME
//   Play requires a versionCode that increases with every upload, so the release
//   workflow derives it from GITHUB_RUN_NUMBER (+ operator offset) instead of
//   editing this file. Locally both fall back to the development defaults.
//   2100000000 is Play's hard ceiling on versionCode.
//
// ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS /
// ANDROID_KEY_PASSWORD
//   The upload key, decoded by the workflow from GitHub secrets onto the runner.
//   When all four are present the release build type is signed with them.
//   When any is absent (local builds, pull requests without secrets) the release
//   build type stays UNSIGNED on purpose: a debug-signed AAB is rejected by Play
//   and must never look uploadable.
// ---------------------------------------------------------------------------
val maxPlayVersionCode = 2_100_000_000

val releaseVersionCode: Int? = System.getenv("ANDROID_VERSION_CODE")?.let { raw ->
    val parsed = raw.toIntOrNull()
    check(parsed != null && parsed in 1..maxPlayVersionCode) {
        "ANDROID_VERSION_CODE must be an integer in 1..$maxPlayVersionCode (got '$raw')"
    }
    parsed
}

val releaseVersionName: String? = System.getenv("ANDROID_VERSION_NAME")?.let { raw ->
    check(raw.isNotBlank()) { "ANDROID_VERSION_NAME must not be blank" }
    raw
}

val releaseSigningValues = listOf(
    System.getenv("ANDROID_KEYSTORE_PATH"),
    System.getenv("ANDROID_KEYSTORE_PASSWORD"),
    System.getenv("ANDROID_KEY_ALIAS"),
    System.getenv("ANDROID_KEY_PASSWORD"),
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }

android {
    // applicationId == namespace == the retained product identity (OQ-03).
    namespace = "com.paolosantucci.foglietto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paolosantucci.foglietto"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "1.0.0"
    }

    // Only created when the workflow supplied the upload key, so a clean clone
    // with ANDROID_HOME alone still configures and builds (debug APK, check).
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH")!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // TASK-23: the shell resolves its copy through `stringResource(R.string.*)`, so the
        // Robolectric suites that host `MainActivity` need the merged app resources. Without this
        // AGP synthesises the package and every app-resource lookup throws
        // `Resources$NotFoundException` (the pre-shell suites only touched `android.*` resources).
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Single source of behaviour: the shared domain/data module (FR-03).
    implementation(project(":shared"))

    // Compose BOM first — it is what gives the version-less Compose artifacts their versions.
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    // The one icon set this app needs: the outlined/filled glyph table the chrome renders.
    // Version-less on purpose — the icons artifacts take their version from the Compose BOM.
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Tier A: plain JVM unit tests (manifest contract, task-graph probes, pure state).
    testImplementation(libs.junit)
    // Tier B: Robolectric-backed suites (motion resolution, intents, lifecycle) run headlessly —
    // the UI-adjacent proof ceiling is Tier B inside `check`; no device or emulator is ever
    // required (FR-38, NFR-08). Tier C instrumentation artifacts are deliberately absent (OQ-20).
    testImplementation(libs.robolectric)
}

// Tier B suites pin the sdk 26 + 36 matrix in their sources (@Config(sdk = [26, 36]), NFR-12).
// Robolectric's API-36 sandbox is built for Java 21, so on the mandated JDK 17 toolchain that leg
// cannot start ("Android SDK 36 requires Java 21 (have Java 17)") and fails in `classMethod`. The
// matrix stays pinned; when the running JVM is older than 21 only the API 26 leg executes and the
// degradation is announced loudly (the full matrix must execute on a JDK 21 host — CI). This
// mirrors the identical guard in `shared/build.gradle.kts` (TASK-02's logged deviation).
tasks.withType<Test>().configureEach {
    if (JavaVersion.current() < JavaVersion.VERSION_21) {
        logger.warn(
            "$name: Robolectric API 36 sandboxes require Java 21 — running the API 26 leg only on " +
                "Java ${JavaVersion.current().majorVersion} (NFR-12 full matrix needs a JDK 21 host)."
        )
        systemProperty("robolectric.enabledSdks", "26")
    }
}
