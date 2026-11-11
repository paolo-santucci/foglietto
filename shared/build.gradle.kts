import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // AGP-9 KMP Android Library plugin (FR-03). `com.android.library` must NEVER be applied
    // alongside `org.jetbrains.kotlin.multiplatform` — AGP 9 forbids the combination.
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    jvmToolchain(17)

    jvm() // hosts jvmTest — the Mac-less local + ubuntu CI verification target (NFR-05, MC-01/MC-03)

    // Android target (FR-03, FR-04). Registers `androidMain` (production actuals/factories,
    // including the `recoveryBaseDir()` actual) and the AGP-9 host-test source set that runs
    // the Robolectric suites headlessly under `./gradlew check` (FR-38, NFR-08, NFR-12).
    androidLibrary {
        namespace = "com.paolosantucci.foglietto.shared"
        compileSdk = 36
        minSdk { version = release(26) }

        // Host (JVM) unit tests via Robolectric; Android resources must be available so the
        // real-Context suites (AndroidContext, recoveryBaseDir) can run without a device.
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }

    // Apple targets are host-guarded: they only register on a macOS host. This keeps `./gradlew check`
    // (which aggregates allTests) green on Linux/CI-ubuntu — where it resolves to jvmTest only, satisfying
    // NFR-07 "shared compiles/tests on the JVM toolchain without an Xcode toolchain". On macOS (the ios.yml
    // CI job) the three iOS targets register and `:shared:assembleSharedReleaseXCFramework` produces the
    // XCFramework consumed by iosApp over SPM (MC-02). The XCFramework task therefore exists only on macOS,
    // which is the only host that calls it.
    if (HostManager.hostIsMac) {
        val xcf = XCFramework("shared")
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
            target.binaries.framework {
                baseName = "shared"
                isStatic = true // static framework — simplest SPM binaryTarget embedding, no dynamic-lib embed step
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // jvmTest inherits kotlin.test transitively from commonTest via the default source set hierarchy.
        // kotlin.test resolves to the JUnit4 adapter on the JVM target automatically.
        //
        // The AGP-9 KMP plugin names its host-test source set `androidHostTest`; the plan pins the
        // Robolectric sources under `shared/src/androidUnitTest`, so that directory is mapped onto it.
        // No Compose/UI test artifact is declared — Tier C is out of scope this wave (OQ-20).
        named("androidHostTest") {
            kotlin.srcDir("src/androidUnitTest/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.junit)
                implementation(libs.robolectric)
            }
        }
    }
}

// NFR-12 pins the sdk 26 + 36 Robolectric matrix in the test sources (`@Config(sdk = [26, 36])`).
// Robolectric's API-36 sandbox is built for Java 21, so on the mandated JDK 17 toolchain that leg
// cannot start ("Android SDK 36 requires Java 21 (have Java 17)") and fails in `classMethod`. The
// matrix stays pinned; when the running JVM is older than 21 only the API 26 leg executes and the
// degradation is announced loudly (the full matrix must execute on a JDK 21 host — CI).
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testAndroidHostTest" && JavaVersion.current() < JavaVersion.VERSION_21) {
        logger.warn(
            "Robolectric: API 36 sandboxes require Java 21 — running the API 26 leg only on " +
                "Java ${JavaVersion.current().majorVersion} (NFR-12 full matrix needs a JDK 21 host)."
        )
        systemProperty("robolectric.enabledSdks", "26")
    }
}
