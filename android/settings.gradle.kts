pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fold-claude-history"
// 원래는 관제실 앱(:app)과 같은 모노레포였다. 공개본은 기록 앱(:history)과 공용 모듈(:core)만 담는다.
include(":core", ":history")
