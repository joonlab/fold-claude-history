import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

// Claude 기록 앱 — 웹 history viewer 의 폴드8 네이티브판. 관제실(:app)과 APK 가 따로다(DESIGN D5).
plugins {
    id("com.android.application")
    // AGP 9 부터 Kotlin 은 AGP 내장 — kotlin.android 플러그인을 넣으면 빌드가 죽는다(android-cli-app 함정 2).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.joonlab.cchistory"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.cchistory"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // 재설치가 진짜 반영됐는지 화면에서 확인하려고 빌드 시각을 박는다(관제실과 같은 방식).
        val stamp = SimpleDateFormat("MM-dd HH:mm").apply {
            timeZone = TimeZone.getTimeZone("Asia/Seoul")
        }.format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$stamp\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    // 반접힘(테이블톱·책) — FoldingFeature 로 힌지 위치·방향을 받는다(DESIGN §4, M4)
    implementation("androidx.window:window:1.5.0")
}
