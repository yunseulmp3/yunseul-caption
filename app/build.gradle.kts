plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yunseul.caption"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.yunseul.caption"
        minSdk = 29          // 다른 앱 소리 캡처(AudioPlaybackCapture)는 안드로이드 10부터
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    signingConfigs {
        // 서명 키는 저장소에 넣지 않음. GitHub Actions가 처음 한 번 만들어 캐시에 보관하고
        // 매 빌드마다 같은 키를 씀 → 폰에서 '업데이트'로 덮어 설치됨
        create("fixed") {
            storeFile = file(System.getenv("SIGNING_STORE") ?: "../keystore/release.jks")
            storePassword = System.getenv("SIGNING_PASS") ?: ""
            keyAlias = "caption"
            keyPassword = System.getenv("SIGNING_PASS") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = false } }
}
