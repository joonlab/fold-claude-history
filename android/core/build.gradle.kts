import java.util.Properties

// 기록 앱(:history)이 쓰는 공용 모듈 — 연결·JSON·테마 주입·글자 크기·칸 레일·아이콘·Pretendard.
// 색은 여기서 정하지 않는다. 앱이 CorePalette 를 주입한다.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 맥 에이전트 주소는 코드에 박지 않는다. android/agent.properties(커밋 안 함) → gradle -P → 환경변수 순으로 읽는다.
// 예시는 android/agent.properties.example. 값이 비어 있는 맥은 앱 목록에서 빠진다.
val agentProps = Properties().apply {
    val f = rootProject.file("agent.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun agentValue(key: String, fallback: String = ""): String {
    val env = key.uppercase().replace('.', '_')
    return agentProps.getProperty(key)
        ?: (project.findProperty(key) as String?)
        ?: System.getenv(env)
        ?: fallback
}

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "kr.joonlab.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        // 두 맥: key 는 코드가 쓰는 고정 이름(home = 먼저 읽는 맥), label 은 화면 표시용
        buildConfigField("String", "AGENT_LAPTOP_LABEL", quoted(agentValue("cchistory.laptop.label", "노트북")))
        buildConfigField("String", "AGENT_LAPTOP_URL", quoted(agentValue("cchistory.laptop.url")))
        buildConfigField("String", "AGENT_HOME_LABEL", quoted(agentValue("cchistory.home.label", "홈맥")))
        buildConfigField("String", "AGENT_HOME_URL", quoted(agentValue("cchistory.home.url")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // 앱이 core 를 쓰면 Compose 도 같이 보이게 api 로 건다(버전은 한 곳에서).
    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.foundation:foundation")
    api("androidx.activity:activity-compose:1.12.4")
}
