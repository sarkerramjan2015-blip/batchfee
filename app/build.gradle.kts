import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseSigningTask : DefaultTask() {
  @get:Input
  abstract val validationMessage: Property<String>

  @TaskAction
  fun verify() {
    validationMessage.orNull?.takeIf { it.isNotBlank() }?.let { message ->
      throw GradleException("Release signing validation failed: $message")
    }
  }
}

plugins {
  alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
  kotlin("plugin.serialization") version "2.2.10"
}

val releaseSigningPropertiesFileName = "keystore.properties"

val localSigningProperties = Properties().apply {
  val propertiesFile = rootProject.file(releaseSigningPropertiesFileName)
  if (propertiesFile.isFile) propertiesFile.inputStream().use(::load)
}

/**
 * CI should supply these through Gradle properties (including ORG_GRADLE_PROJECT_* environment
 * variables). The ignored local properties file is only a developer convenience. Legacy names are
 * accepted for existing local setups, but are never written to the repository or build output.
 */
fun releaseSigningValue(propertyName: String, legacyPropertyName: String): String? =
  providers.gradleProperty(propertyName).orNull?.trim()?.takeIf(String::isNotEmpty)
    ?: localSigningProperties.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
    ?: localSigningProperties.getProperty(legacyPropertyName)?.trim()?.takeIf(String::isNotEmpty)

data class ReleaseSigningInput(
  val storeFileValue: String?,
  val storePassword: String?,
  val keyAliasValue: String?,
  val keyPassword: String?,
)

val releaseSigningInput = ReleaseSigningInput(
  storeFileValue = releaseSigningValue("releaseStoreFile", "storeFile"),
  storePassword = releaseSigningValue("releaseStorePassword", "storePassword"),
  keyAliasValue = releaseSigningValue("releaseKeyAlias", "keyAlias"),
  keyPassword = releaseSigningValue("releaseKeyPassword", "keyPassword"),
)

fun releaseSigningValidationError(input: ReleaseSigningInput): String? {
  val missing = buildList {
    if (input.storeFileValue == null) add("releaseStoreFile")
    if (input.storePassword == null) add("releaseStorePassword")
    if (input.keyAliasValue == null) add("releaseKeyAlias")
    if (input.keyPassword == null) add("releaseKeyPassword")
  }
  if (missing.isNotEmpty()) {
    return "Missing required release signing properties: ${missing.joinToString()}. " +
      "Use CI secret Gradle properties or the ignored $releaseSigningPropertiesFileName file."
  }
  val resolvedStoreFile = rootProject.file(requireNotNull(input.storeFileValue))
  if (!resolvedStoreFile.isFile || !resolvedStoreFile.canRead()) {
    return "Release keystore file is missing or unreadable."
  }
  if (resolvedStoreFile.name.equals("debug.keystore", ignoreCase = true) ||
      input.keyAliasValue.equals("androiddebugkey", ignoreCase = true)) {
    return "Debug signing keys are forbidden for release artifacts."
  }
  return null
}

val releaseSigningValidationMessage = releaseSigningValidationError(releaseSigningInput)

val releaseSigningPreflight = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
  group = "verification"
  description = "Fails release artifact creation unless a non-debug release keystore is configured."
  validationMessage.set(releaseSigningValidationMessage.orEmpty())
}

android {
  namespace = "com.batchfee.edu"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.batchfee.edu"
    minSdk = 24
    targetSdk = 36
    versionCode = 9
    versionName = "1.6.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseSigningValidationError(releaseSigningInput) == null) {
      create("release") {
        storeFile = rootProject.file(requireNotNull(releaseSigningInput.storeFileValue))
        storePassword = requireNotNull(releaseSigningInput.storePassword)
        keyAlias = requireNotNull(releaseSigningInput.keyAliasValue)
        keyPassword = requireNotNull(releaseSigningInput.keyPassword)
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// These are the only tasks that create or validate a distributable release artifact. Compilation
// and lint remain usable without private signing material, but no APK/AAB can be packaged unsigned
// or with the debug key.
tasks.configureEach {
  val isReleaseArtifactTask = name in setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "signReleaseBundle",
    "validateSigningRelease",
    "zipAlignRelease",
  ) || (name.contains("Release", ignoreCase = true) &&
    (name.startsWith("package", ignoreCase = true) ||
      name.startsWith("sign", ignoreCase = true) ||
      name.startsWith("bundle", ignoreCase = true) ||
      name.startsWith("assemble", ignoreCase = true)))
  if (isReleaseArtifactTask) {
    dependsOn(releaseSigningPreflight)
    notCompatibleWithConfigurationCache(
      "Release signing credentials must not be retained in the Gradle configuration cache.",
    )
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.appcheck.playintegrity)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.functions)
  implementation(libs.firebase.crashlytics)
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.firebase.appcheck.debug)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
