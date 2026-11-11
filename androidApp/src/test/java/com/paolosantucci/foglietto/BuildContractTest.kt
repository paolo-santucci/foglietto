package com.paolosantucci.foglietto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A build contract — plain JVM, no Robolectric, no Gradle API (FR-37, FR-38, NFR-08;
 * plan §4.4 / TASK-06).
 *
 * The module build scripts are read as text and pinned against the contract the plan fixes for
 * this wave:
 *
 *  - **SDK levels** — `compileSdk = 36`, `targetSdk = 36` (app) and `minSdk = 26` on both
 *    modules; every declared `targetSdk` in the tree is 36. The app module is the only one whose
 *    `targetSdk` is an install-time contract (an AAR's `targetSdk` is inert for consumers), so
 *    `shared/` is pinned on `compileSdk`/`minSdk` only.
 *  - **No local machine state** — no `local.properties` may be referenced and no keystore path may
 *    be hard-coded: a clean clone on Linux/JDK 17 must assemble the debug APK with `ANDROID_HOME`
 *    alone. Release signing is allowed, but only as an env-gated configuration whose key material
 *    arrives from CI secrets (`ANDROID_KEYSTORE_PATH` and friends); the tree itself names no
 *    keystore file and falls back to an unsigned release build when the env is absent.
 *  - **AGP-9 toolchain** — the legacy `org.jetbrains.kotlin.android` plugin is never applied and
 *    `kotlinOptions` never appears (AGP 9 has built-in Kotlin and follows `compileOptions`); every
 *    plugin is reached through the version catalog (`alias(libs.plugins.…)`).
 *  - **Tier C stays out** — `androidApp/src/androidTest` does not exist and the app declares no
 *    instrumentation dependency, so no Compose UI test can ever enter the green path (OQ-20).
 *
 * The companion probe for the *task graph* (no `connected` task and no instrumentation execution
 * inside `check`) cannot be answered from inside a JVM test; it is run as a command-level
 * assertion next to this suite and is reported with the task.
 */
class BuildContractTest {

    private val appBuildFile: File by lazy {
        resolve(
            marker = APP_MARKER,
            candidates = listOf("build.gradle.kts", "androidApp/build.gradle.kts"),
        )
    }

    private val sharedBuildFile: File by lazy {
        resolve(
            marker = SHARED_MARKER,
            candidates = listOf(
                "../shared/build.gradle.kts",
                "shared/build.gradle.kts",
            ),
        )
    }

    private val appBuild: String by lazy { appBuildFile.declarationCode() }
    private val sharedBuild: String by lazy { sharedBuildFile.declarationCode() }

    @Test
    fun `the app module declares compileSdk 36 targetSdk 36 and minSdk 26`() {
        assertDeclares(appBuild, APP_MARKER, """^\s*compileSdk\s*=\s*36\s*$""", "compileSdk = 36")
        assertDeclares(appBuild, APP_MARKER, """^\s*targetSdk\s*=\s*36\s*$""", "targetSdk = 36")
        assertDeclares(appBuild, APP_MARKER, """^\s*minSdk\s*=\s*26\s*$""", "minSdk = 26")
    }

    @Test
    fun `the shared module declares compileSdk 36 and minSdk 26`() {
        assertDeclares(sharedBuild, SHARED_MARKER, """^\s*compileSdk\s*=\s*36\s*$""", "compileSdk = 36")
        // AGP-9 KMP library DSL: `minSdk { version = release(26) }`.
        assertDeclares(
            sharedBuild,
            SHARED_MARKER,
            """minSdk\s*\{\s*version\s*=\s*release\(26\)\s*\}""",
            "minSdk { version = release(26) }",
        )
    }

    @Test
    fun `no module downgrades targetSdk below 36`() {
        val declared = TARGET_SDK.findAll("$appBuild\n$sharedBuild").toList()

        assertEquals(
            "The app module must be the module that declares targetSdk 36 (install-time contract).",
            listOf("36"),
            TARGET_SDK.findAll(appBuild).map { it.groupValues[1] }.toList(),
        )
        declared.forEach { match ->
            assertEquals(
                "Every declared targetSdk must be 36; found ${match.value.trim()}.",
                "36",
                match.groupValues[1],
            )
        }
    }

    @Test
    fun `the build scripts never depend on local properties`() {
        listOf(appBuildFile to appBuild, sharedBuildFile to sharedBuild).forEach { (file, code) ->
            assertFalse(
                "${file.name} must not reference local.properties — a clean clone must build " +
                    "with ANDROID_HOME alone (FR-37).",
                code.lowercase().contains("local.properties"),
            )
        }
    }

    @Test
    fun `release signing is environment-gated and names no committed keystore`() {
        // The Play release wave signs the AAB, but the upload key lives in GitHub secrets and is
        // decoded onto the runner. Three things keep that honest:
        //  a) every signing input is read from an environment variable;
        //  b) the store file is never a literal repo path (no `file("…keystore")`);
        //  c) the release build type is only bound to the config when the env is complete, so a
        //     clean clone still configures and builds an unsigned release.
        listOf(
            "ANDROID_KEYSTORE_PATH",
            "ANDROID_KEYSTORE_PASSWORD",
            "ANDROID_KEY_ALIAS",
            "ANDROID_KEY_PASSWORD",
        ).forEach { name ->
            assertTrue(
                "androidApp/build.gradle.kts must read $name from the environment " +
                    "(secrets are never committed).",
                appBuild.contains("""System.getenv("$name")"""),
            )
        }

        assertFalse(
            "androidApp/build.gradle.kts must not hard-code a keystore path: the store file must " +
                "come from ANDROID_KEYSTORE_PATH, not from a file in the tree.",
            Regex("""storeFile\s*=\s*file\(\s*"""").containsMatchIn(appBuild),
        )
        assertFalse(
            "androidApp/build.gradle.kts must not name a keystore file — key material is provisioned " +
                "by CI, never committed (FR-37).",
            appBuild.contains(".keystore") || appBuild.contains(".jks"),
        )

        assertTrue(
            "The release build type must bind the signing config only behind the env guard, so an " +
                "unsigned release build still configures on a clean clone.",
            appBuild.contains("if (hasReleaseSigning)") &&
                appBuild.contains("""signingConfig = signingConfigs.getByName("release")"""),
        )
    }

    @Test
    fun `the version identity is overridable from CI without editing the tree`() {
        // Play rejects a repeated versionCode, so the release workflow supplies a fresh one per
        // run. A hard-coded versionCode would force a source edit before every upload.
        assertTrue(
            "androidApp/build.gradle.kts must read ANDROID_VERSION_CODE from the environment.",
            appBuild.contains("""System.getenv("ANDROID_VERSION_CODE")"""),
        )
        assertTrue(
            "androidApp/build.gradle.kts must read ANDROID_VERSION_NAME from the environment.",
            appBuild.contains("""System.getenv("ANDROID_VERSION_NAME")"""),
        )
    }

    @Test
    fun `the legacy kotlin android plugin and kotlinOptions are never used`() {
        listOf(appBuildFile to appBuild, sharedBuildFile to sharedBuild).forEach { (file, code) ->
            assertFalse(
                "${file.name} must never apply org.jetbrains.kotlin.android: AGP 9 has built-in " +
                    "Kotlin and the combination is incompatible (TASK-01/TASK-03).",
                code.contains("org.jetbrains.kotlin.android"),
            )
            assertFalse(
                "${file.name} must never use kotlinOptions: the Kotlin target follows " +
                    "compileOptions on AGP 9.",
                code.contains("kotlinOptions"),
            )
        }
    }

    @Test
    fun `every plugin is reached through the version catalog`() {
        assertDeclares(appBuild, APP_MARKER, """alias\(libs\.plugins\.androidApplication\)""", "androidApplication alias")
        assertDeclares(appBuild, APP_MARKER, """alias\(libs\.plugins\.composeCompiler\)""", "composeCompiler alias")
        assertDeclares(
            sharedBuild,
            SHARED_MARKER,
            """alias\(libs\.plugins\.androidKotlinMultiplatformLibrary\)""",
            "androidKotlinMultiplatformLibrary alias",
        )
    }

    @Test
    fun `the app applies the compose BOM before the compose artifacts`() {
        assertDeclares(
            appBuild,
            APP_MARKER,
            """implementation\(platform\(libs\.compose\.bom\)\)""",
            "implementation(platform(libs.compose.bom))",
        )
    }

    @Test
    fun `the app unit-test classpath can run Tier B Robolectric suites headlessly`() {
        // FR-38 / NFR-08: the UI-adjacent proof ceiling is Tier B (Robolectric inside `check`).
        // Without these two artifacts the app's Tier A/B suites cannot even compile, which would
        // silently remove them from the green path.
        assertDeclares(appBuild, APP_MARKER, """testImplementation\(libs\.junit\)""", "testImplementation(libs.junit)")
        assertDeclares(
            appBuild,
            APP_MARKER,
            """testImplementation\(libs\.robolectric\)""",
            "testImplementation(libs.robolectric)",
        )
    }

    @Test
    fun `the app compiles against the JDK 17 language level`() {
        assertDeclares(
            appBuild,
            APP_MARKER,
            """sourceCompatibility\s*=\s*JavaVersion\.VERSION_17""",
            "sourceCompatibility = JavaVersion.VERSION_17",
        )
        assertDeclares(
            appBuild,
            APP_MARKER,
            """targetCompatibility\s*=\s*JavaVersion\.VERSION_17""",
            "targetCompatibility = JavaVersion.VERSION_17",
        )
    }

    @Test
    fun `Tier C instrumentation is excluded structurally`() {
        val androidTestSourceSet = File(appBuildFile.parentFile, "src/androidTest")
        assertFalse(
            "androidApp/src/androidTest must not exist this wave: an empty instrumentation " +
                "source set is the false-green trap the plan resolves in OQ-20.",
            androidTestSourceSet.exists(),
        )

        listOf(
            "androidTestImplementation",
            "androidTestUtil",
            "compose.ui.test",
            "ui-test-junit4",
            "ui-test-manifest",
            "managedDevices",
        ).forEach { token ->
            assertFalse(
                "androidApp/build.gradle.kts must not declare '$token' — Compose UI tests are " +
                    "Tier C and never enter the green path (OQ-20).",
                appBuild.lowercase().contains(token.lowercase()),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun resolve(marker: String, candidates: List<String>): File =
        candidates
            .map(::File)
            .firstOrNull { it.isFile && it.readText().contains(marker) }
            ?: error(
                "No build script containing '$marker' found among " +
                    "${candidates.joinToString()} from ${File(".").absolutePath}",
            )

    /**
     * The script with block and line comments removed, so assertions pin *declarations*, not the
     * prose that explains them.
     */
    private fun File.declarationCode(): String =
        readText()
            .replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private fun assertDeclares(code: String, marker: String, regex: String, expected: String) {
        assertTrue(
            "Expected '$marker' to declare `$expected` (pattern /$regex/), but it does not.",
            Regex(regex, RegexOption.MULTILINE).containsMatchIn(code),
        )
    }

    private companion object {
        const val APP_MARKER = "namespace = \"com.paolosantucci.foglietto\""
        const val SHARED_MARKER = "alias(libs.plugins.androidKotlinMultiplatformLibrary)"

        val TARGET_SDK = Regex("""(?m)^\s*targetSdk\s*=\s*(\d+)\s*$""")
        val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    }
}
