import java.util.Properties

plugins { id("com.android.application") }

// リリース署名の鍵はリポジトリの外（~/.android）に置き、パスワードもコミットしない
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android/gamemode-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.gamemode"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.gamemode"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // 難読化はしない（Shizuku の非公開メソッドをリフレクションで呼んでいるため、縮小すると壊れる）
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
