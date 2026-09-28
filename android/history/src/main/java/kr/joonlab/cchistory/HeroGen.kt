package kr.joonlab.cchistory

// ⚠️ 생성 파일 — 손으로 고치지 말 것. 원천: android/tools/gen_heroicons.py (Heroicons v1 outline, MIT).

/** Heroicons 이름 → SVG path 목록(24×24, stroke 2, round). 호 플래그는 띄어 쓴 꼴로 풀어 두었다. */
val HERO_PATHS: Map<String, List<String>> = mapOf(
    "search" to listOf("M21 21l-6 -6m2 -5a7 7 0 1 1 -14 0 7 7 0 0 1 14 0z"),
    "folder" to listOf("M3 7v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2 -2V9a2 2 0 0 0 -2 -2h-6l-2 -2H5a2 2 0 0 0 -2 2z"),
    "bookmark" to listOf("M5 5a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v16l-7 -3.5L5 21V5z"),
    "calendar" to listOf("M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 0 0 2 -2V7a2 2 0 0 0 -2 -2H5a2 2 0 0 0 -2 2v12a2 2 0 0 0 2 2z"),
    "tag" to listOf("M7 7h.01M7 3h5c.512 0 1.024 .195 1.414 .586l7 7a2 2 0 0 1 0 2.828l-7 7a2 2 0 0 1 -2.828 0l-7 -7A1.994 1.994 0 0 1 3 12V7a4 4 0 0 1 4 -4z"),
    "menu" to listOf("M4 6h16M4 12h16M4 18h16"),
    "arrow-down" to listOf("M19 14l-7 7m0 0l-7 -7m7 7V3"),
    "arrow-up" to listOf("M5 10l7 -7m0 0l7 7m-7 -7v18"),
    "chevron-left" to listOf("M15 19l-7 -7 7 -7"),
    "chevron-right" to listOf("M9 5l7 7 -7 7"),
    "chevron-down" to listOf("M19 9l-7 7 -7 -7"),
    "chevron-up" to listOf("M5 15l7 -7 7 7"),
    "eye" to listOf("M15 12a3 3 0 1 1 -6 0 3 3 0 0 1 6 0z", "M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7 -1.274 4.057 -5.064 7 -9.542 7 -4.477 0 -8.268 -2.943 -9.542 -7z"),
    "home" to listOf("M3 12l2 -2m0 0l7 -7 7 7M5 10v10a1 1 0 0 0 1 1h3m10 -11l2 2m-2 -2v10a1 1 0 0 1 -1 1h-3m-6 0a1 1 0 0 0 1 -1v-4a1 1 0 0 1 1 -1h2a1 1 0 0 1 1 1v4a1 1 0 0 0 1 1m-6 0h6"),
    "desktop" to listOf("M9.75 17L9 20l-1 1h8l-1 -1 -.75 -3M3 13h18M5 17h14a2 2 0 0 0 2 -2V5a2 2 0 0 0 -2 -2H5a2 2 0 0 0 -2 2v10a2 2 0 0 0 2 2z"),
    "moon" to listOf("M20.354 15.354A9 9 0 0 1 8.646 3.646 9.003 9.003 0 0 0 12 21a9.003 9.003 0 0 0 8.354 -5.646z"),
    "sun" to listOf("M12 3v1m0 16v1m9 -9h-1M4 12H3m15.364 6.364l-.707 -.707M6.343 6.343l-.707 -.707m12.728 0l-.707 .707M6.343 17.657l-.707 .707M16 12a4 4 0 1 1 -8 0 4 4 0 0 1 8 0z"),
    "refresh" to listOf("M4 4v5h.582m15.356 2A8.001 8.001 0 0 0 4.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 0 1 -15.357 -2m15.357 2H15"),
    "x" to listOf("M6 18L18 6M6 6l12 12"),
    "link" to listOf("M13.828 10.172a4 4 0 0 0 -5.656 0l-4 4a4 4 0 1 0 5.656 5.656l1.102 -1.101m-.758 -4.899a4 4 0 0 0 5.656 0l4 -4a4 4 0 0 0 -5.656 -5.656l-1.1 1.1"),
    "columns" to listOf("M9 17V7m0 10a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V7a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2m0 10a2 2 0 0 0 2 2h2a2 2 0 0 0 2 -2M9 7a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2m0 10V7m0 10a2 2 0 0 0 2 2h2a2 2 0 0 0 2 -2V7a2 2 0 0 0 -2 -2h-2a2 2 0 0 0 -2 2"),
    "filter" to listOf("M3 4a1 1 0 0 1 1 -1h16a1 1 0 0 1 1 1v2.586a1 1 0 0 1 -.293 .707l-6.414 6.414a1 1 0 0 0 -.293 .707V17l-4 4v-6.586a1 1 0 0 0 -.293 -.707L3.293 7.293A1 1 0 0 1 3 6.586V4z"),
    "chat" to listOf("M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418 -4.03 8 -9 8a9.863 9.863 0 0 1 -4.255 -.949L3 20l1.395 -3.72C3.512 15.042 3 13.574 3 12c0 -4.418 4.03 -8 9 -8s9 3.582 9 8z"),
    "clock" to listOf("M12 8v4l3 3m6 -3a9 9 0 1 1 -18 0 9 9 0 0 1 18 0z"),
    "play" to listOf("M14.752 11.168l-3.197 -2.132A1 1 0 0 0 10 9.87v4.263a1 1 0 0 0 1.555 .832l3.197 -2.132a1 1 0 0 0 0 -1.664z", "M21 12a9 9 0 1 1 -18 0 9 9 0 0 1 18 0z"),
    "terminal" to listOf("M8 9l3 3 -3 3m5 0h3M5 20h14a2 2 0 0 0 2 -2V6a2 2 0 0 0 -2 -2H5a2 2 0 0 0 -2 2v12a2 2 0 0 0 2 2z"),
)
