import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
}

// Firebase options come from app/google-services.json when present (never committed).
// Without it the app targets the local Emulator Suite using the offline "demo-nexora" project.
val firebase = mutableMapOf(
    "projectId" to "demo-nexora",
    "apiKey" to "demo-api-key",
    "appId" to "1:000000000000:android:0000000000000000",
    "senderId" to "000000000000",
)
val googleServices = file("google-services.json")
if (googleServices.exists()) {
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(googleServices) as Map<String, Any?>
    val info = json["project_info"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val client = (json["client"] as List<Map<String, Any?>>).first {
        val ci = it["client_info"] as Map<String, Any?>
        (ci["android_client_info"] as Map<String, Any?>)["package_name"] == "com.nexora"
    }
    @Suppress("UNCHECKED_CAST")
    val keys = client["api_key"] as List<Map<String, Any?>>
    firebase["projectId"] = info["project_id"].toString()
    firebase["senderId"] = info["project_number"].toString()
    firebase["appId"] = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString()
    firebase["apiKey"] = keys.first()["current_key"].toString()
}
// Live Firebase whenever google-services.json is present; -Pnexora.useEmulator=true forces the local Emulator Suite.
val useEmulator = providers.gradleProperty("nexora.useEmulator").getOrElse((!googleServices.exists()).toString()).toBoolean()
val emulatorHost = providers.gradleProperty("nexora.emulatorHost").getOrElse("10.0.2.2")

android {
    namespace = "com.nexora"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nexora"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        firebase.forEach { (k, v) ->
            buildConfigField("String", "FIREBASE_" + k.uppercase(), "\"$v\"")
        }
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            buildConfigField("boolean", "USE_EMULATOR", useEmulator.toString())
            manifestPlaceholders["cleartext"] = useEmulator.toString()
        }
        release {
            buildConfigField("boolean", "USE_EMULATOR", "false")
            manifestPlaceholders["cleartext"] = "false"
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)
    implementation(libs.fragment)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.core.testing)
}
