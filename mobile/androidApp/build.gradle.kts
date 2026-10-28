import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Platform settings are configuration, not code: a Gradle property (-P… or gradle.properties) or
 * mobile/local.properties (git-ignored, per machine). docs/platform.md lists them:
 *
 *   platform.baseUrl         the platform API              (debug default: the development stack)
 *   platform.oidcIssuer      the platform's Zitadel        (debug default: the development stack)
 *   platform.devCa           the development stack's CA certificate; debug builds trust it
 *   simpletemplate.oidcClientId   this app's own native OIDC client in Zitadel
 *
 * Release builds have no defaults: a release built without them cannot sign in, rather than
 * reaching the wrong server.
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(name: String): String? = (project.findProperty(name) as String?) ?: localProperties.getProperty(name)

/**
 * The sign-in redirect scheme: one reverse-DNS scheme per app (platform contract §2), so Android
 * never has to choose between two installed apps for the sign-in result. scripts/new-app.sh names
 * it after the app's slug; it must match the redirect URI of the app's native application in the
 * platform's Zitadel (`<scheme>:/oidc/callback`, post-logout `<scheme>:/oidc/logout`).
 */
val oidcScheme = "eu.mysimple.simpletemplate"

/**
 * Debug builds trust the development stack's own CA when `platform.devCa` names its public
 * certificate (iso/deploy/traefik/certs/ca.crt): a generated network security config with
 * debug-overrides. Release builds never see it and trust the system's CAs only.
 */
abstract class DevNetworkConfigTask : DefaultTask() {
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val caFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val res = outputDir.get().asFile
        res.deleteRecursively()
        val xml = File(res, "xml").apply { mkdirs() }
        val ca = caFile.orNull?.asFile
        val trust = if (ca != null) {
            File(res, "raw").mkdirs()
            ca.copyTo(File(res, "raw/dev_ca"), overwrite = true)
            """
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/dev_ca" />
        </trust-anchors>
    </debug-overrides>"""
        } else ""
        File(xml, "network_security_config.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated for debug builds (androidApp/build.gradle.kts). -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />$trust
</network-security-config>
""",
        )
    }
}

val devNetworkConfig = tasks.register<DevNetworkConfigTask>("devNetworkConfig") {
    setting("platform.devCa")?.let { caFile.set(file(it)) }
    outputDir.set(layout.buildDirectory.dir("generated/devNetworkConfig/res"))
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "debug") {
            variant.sources.res?.addGeneratedSourceDirectory(devNetworkConfig, DevNetworkConfigTask::outputDir)
        }
    }
}

android {
    namespace = "app.simpletemplate.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.simpletemplate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
        manifestPlaceholders["oidcScheme"] = oidcScheme
        buildConfigField("String", "OIDC_SCHEME", "\"$oidcScheme\"")
        buildConfigField("String", "BASE_URL", "\"${setting("platform.baseUrl") ?: "https://platform.not-configured.invalid"}\"")
        buildConfigField("String", "OIDC_ISSUER", "\"${setting("platform.oidcIssuer") ?: "https://auth.not-configured.invalid"}\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"${setting("simpletemplate.oidcClientId") ?: ""}\"")
        buildConfigField("boolean", "LOOPBACK_LOCALHOST", "false")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // The platform's development stack on the developer's computer (iso/deploy,
            // BASE_DOMAIN=mysimple.localhost), reached through adb reverse (mobile/tools/dev-device.sh).
            buildConfigField("String", "BASE_URL", "\"${setting("platform.baseUrl") ?: "https://api.mysimple.localhost"}\"")
            buildConfigField("String", "OIDC_ISSUER", "\"${setting("platform.oidcIssuer") ?: "https://auth.mysimple.localhost"}\"")
            buildConfigField("boolean", "LOOPBACK_LOCALHOST", "true")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
}
