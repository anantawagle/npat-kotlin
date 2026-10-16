import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val uploadProperties = Properties().apply {
    val propertiesFile = rootProject.file(".signing/upload.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
fun signingValue(key: String, environment: String): String? = System.getenv(environment) ?: uploadProperties.getProperty(key)
val uploadStore = signingValue("storeFile", "NPAT_UPLOAD_STORE_FILE")
android {
    namespace = "com.statsupdate.npat"
    compileSdk = 36
    defaultConfig { applicationId = "com.statsupdate.npat"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "1.2" }
    signingConfigs {
        if (uploadStore != null) create("upload") {
            storeFile = rootProject.file(uploadStore)
            storePassword = signingValue("storePassword", "NPAT_UPLOAD_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "NPAT_UPLOAD_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "NPAT_UPLOAD_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(uploadStore != null && rootProject.file(uploadStore).isFile &&
            !signingValue("storePassword", "NPAT_UPLOAD_STORE_PASSWORD").isNullOrBlank() &&
            !signingValue("keyAlias", "NPAT_UPLOAD_KEY_ALIAS").isNullOrBlank() &&
            !signingValue("keyPassword", "NPAT_UPLOAD_KEY_PASSWORD").isNullOrBlank()) {
            "Configure .signing/upload.properties or NPAT_UPLOAD_* environment variables before building a release. See release/README.md."
        }
    }
}
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach { dependsOn(verifyReleaseSigning) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
