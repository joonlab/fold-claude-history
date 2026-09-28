"""기록 앱 아이콘(Heroicons v1 outline, MIT) → history/.../HeroGen.kt. 웹 뷰어가 쓰는 아이콘 계열(DESIGN D6).
실행: python3 android/tools/gen_heroicons.py

Heroicons v1 의 path 는 호 명령의 플래그를 붙여 쓴다(`a2 2 0 002 2` = 플래그 0,0 + 좌표 2,2).
Compose PathParser 가 이걸 숫자 «002» 로 읽을 수 있어서, 여기서 플래그를 띄어 쓴 꼴로 풀어 낸다.
"""
import os
import re

P = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = P + "/history/src/main/java/kr/joonlab/cchistory/HeroGen.kt"

# 이름 → path 목록 (heroicons v1.0.6 outline, viewBox 0 0 24 24, stroke-width 2)
HERO = {
    "search": ["M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"],
    "folder": ["M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"],
    "bookmark": ["M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z"],
    "calendar": ["M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"],
    "tag": ["M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"],
    "menu": ["M4 6h16M4 12h16M4 18h16"],
    "arrow-down": ["M19 14l-7 7m0 0l-7-7m7 7V3"],
    "arrow-up": ["M5 10l7-7m0 0l7 7m-7-7v18"],
    "chevron-left": ["M15 19l-7-7 7-7"],
    "chevron-right": ["M9 5l7 7-7 7"],
    "chevron-down": ["M19 9l-7 7-7-7"],
    "chevron-up": ["M5 15l7-7 7 7"],
    "eye": ["M15 12a3 3 0 11-6 0 3 3 0 016 0z",
            "M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"],
    "home": ["M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"],
    "desktop": ["M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"],
    "moon": ["M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"],
    "sun": ["M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"],
    "refresh": ["M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"],
    "x": ["M6 18L18 6M6 6l12 12"],
    "link": ["M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"],
    "columns": ["M9 17V7m0 10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h2a2 2 0 012 2m0 10a2 2 0 002 2h2a2 2 0 002-2M9 7a2 2 0 012-2h2a2 2 0 012 2m0 10V7m0 10a2 2 0 002 2h2a2 2 0 002-2V7a2 2 0 00-2-2h-2a2 2 0 00-2 2"],
    "filter": ["M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"],
    "chat": ["M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"],
    "clock": ["M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"],
    # M5 관제실 연동 — 이어가기(play) · 관제실로(terminal)
    "play": ["M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z",
             "M21 12a9 9 0 11-18 0 9 9 0 0118 0z"],
    "terminal": ["M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"],
}

NUM = re.compile(r"-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?")


def expand(d):
    """path 를 명령·숫자로 쪼개 다시 쓴다. 호(a/A)의 4·5번째 인자(플래그)는 한 글자씩 읽는다."""
    out, i, cmd, argi = [], 0, "", 0
    while i < len(d):
        ch = d[i]
        if ch.isalpha():
            cmd, argi = ch, 0
            out.append(ch)
            i += 1
            continue
        if ch in " ,\t\n":
            i += 1
            continue
        if cmd and cmd in "aA" and argi % 7 in (3, 4):
            out.append(ch)          # 플래그 0/1 — 한 글자
            i += 1
        else:
            m = NUM.match(d, i)
            if not m:
                raise ValueError(f"해석 못 함: {d[i:i+12]!r}")
            out.append(m.group(0))
            i = m.end()
        argi += 1
    s = ""
    for t in out:
        s += t if (t.isalpha() or not s or s[-1].isalpha()) else " " + t
    return s


lines = [f'    "{k}" to listOf(' + ", ".join('"' + expand(d) + '"' for d in v) + "),"
         for k, v in HERO.items()]
src = f"""package kr.joonlab.cchistory

// ⚠️ 생성 파일 — 손으로 고치지 말 것. 원천: android/tools/gen_heroicons.py (Heroicons v1 outline, MIT).

/** Heroicons 이름 → SVG path 목록(24×24, stroke 2, round). 호 플래그는 띄어 쓴 꼴로 풀어 두었다. */
val HERO_PATHS: Map<String, List<String>> = mapOf(
{chr(10).join(lines)}
)
"""
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write(src)
print(f"{len(HERO)}개 → {OUT}")
