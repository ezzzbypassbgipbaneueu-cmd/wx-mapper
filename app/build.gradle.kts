import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlinx-serialization")
}

android {

    namespace = "com.wx.mapper"
    compileSdk = 35

    // Assinatura de release é opcional: sem as propriedades, o release sai assinado com a chave debug
    val hasReleaseKey = project.hasProperty("storeFile")
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(property("storeFile") as String)
                storePassword = property("storePassword") as String
                keyAlias = property("keyAlias") as String
                keyPassword = property("keyPassword") as String
            }
        }
    }


    defaultConfig {
        applicationId = "com.wx.mapper"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-wx"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Release NÃO depurável já resolve a maior parte da lentidão do debug.
            // R8 desligado de propósito: o app usa reflexão em APIs do sistema e não há regras de
            // proteção testadas; ligar sem testar pode causar crash. Dá para tentar depois.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        // O lint do release pode barrar o build por avisos que não afetam o uso.
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    ksp("com.google.dagger:hilt-android-compiler:2.55")
    implementation("com.google.dagger:hilt-android:2.55")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.api)
    // Libera APIs ocultas do Android (usado só pelo modo gravação SKIP_SCREENSHOT).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation(libs.provider)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}