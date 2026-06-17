plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.hanada.fx_signal_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        // flutter_local_notifications が java.time を使うため必須。
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.hanada.fx_signal_app"
        // flutter_local_notifications / workmanager の要件に合わせて minSdk 23。
        minSdk = 23
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        multiDexEnabled = true
    }

    signingConfigs {
        // 固定の署名鍵。毎回同じ鍵で署名されるため、APKを「上書きインストール」で
        // 更新できる（アンインストール不要）。個人利用向けの簡易設定。
        create("fxsignal") {
            storeFile = file("fxsignal-keystore.jks")
            storePassword = "fxsignal123"
            keyAlias = "fxsignal"
            keyPassword = "fxsignal123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("fxsignal")
        }
        debug {
            signingConfig = signingConfigs.getByName("fxsignal")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
