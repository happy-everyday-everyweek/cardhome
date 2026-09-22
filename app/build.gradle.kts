import java.util.Properties

plugins {
    id("com.android.application")
}

// 发行签名：默认用仓库里自带的测试密钥，保证本机和 CI 打出来的 release 包签名一致；
// 想换自己的密钥就在根目录放 keystore.properties（storeFile / storePassword / keyAlias / keyPassword）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStorePath = keystoreProps.getProperty("storeFile") ?: "keystore/cardhome-release.jks"
val releaseStorePassword = keystoreProps.getProperty("storePassword") ?: "cardhome"
val releaseKeyAlias = keystoreProps.getProperty("keyAlias") ?: "cardhome"
val releaseKeyPassword = keystoreProps.getProperty("keyPassword") ?: "cardhome"
val hasReleaseSigning = rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.cardhome.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cardhome.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 13
        versionName = "1.4.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有提供正式签名就退回 debug 签名，保证产物永远可以直接安装
            signingConfig =
                if (hasReleaseSigning) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Material Design 3 组件与主题
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}