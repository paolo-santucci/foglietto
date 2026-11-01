import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(17)

    jvm() // hosts jvmTest — the Mac-less local + ubuntu CI verification target (NFR-05, MC-01/MC-03)

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
    }
}
