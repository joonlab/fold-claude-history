package kr.joonlab.cchistory

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.joonlab.core.CorePalette
import kr.joonlab.core.ThemeMode
import kr.joonlab.core.strokeIcon

/**
 * 크림슨 팔레트 — 웹 history viewer 의 색 토큰을 그대로 옮겼다(라이트 / 다크).
 * getter 가 ThemeMode.dark 를 읽으므로 테마를 바꾸면 읽은 자리만 다시 그려진다.
 */
object H : CorePalette {
    private fun p(light: Long, dark: Long) = Color(if (ThemeMode.dark) dark else light)

    override val bg get() = p(0xFFFFFFFF, 0xFF141414)          // --bg-paper
    override val panel get() = p(0xFFF6F6F7, 0xFF1A1A1B)       // --bg-primary
    override val panel2 get() = p(0xFFEEEEF0, 0xFF232325)      // --bg-secondary
    override val raised get() = p(0xFFFFFFFF, 0xFF232325)
    override val border get() = p(0xFFE3E3E5, 0xFF38383A)
    override val text get() = p(0xFF1A1A1A, 0xFFECECEC)
    override val dim get() = p(0xFF6B6B6B, 0xFFA6A6A8)         // --text-secondary
    override val accent get() = p(0xFFA50034, 0xFFE14C74)      // crimson accent
    override val accentTint get() = p(0xFFFBEAF0, 0xFF3A0E1F)  // --accent-light
    override val onAccent get() = Color.White

    val tertiary get() = p(0xFFE5E5E7, 0xFF2C2C2E)
    val muted get() = Color(0xFF8A8D8F)                          // silver
    val borderHover get() = p(0xFFC9CACC, 0xFF515153)
    val gold get() = p(0xFF85714D, 0xFFC2A878)                   // --assistant-accent (gold)
    val goldTint get() = p(0xFFF4EFE5, 0xFF2A2418)               // --assistant-light
    val goldText get() = p(0xFF6E5C3B, 0xFFD9C49A)
    val goldLine get() = p(0xFFD9CDB6, 0xFF4A3F2C)
    val danger get() = p(0xFFC0392B, 0xFFE06C64)
    val success get() = p(0xFF4E7A3F, 0xFF7FA766)
    // 토큰 밖 하드코딩 색
    val tagBg get() = p(0xFFE0F2FE, 0xFF0C2A3D)
    val tagFg get() = p(0xFF0369A1, 0xFF7CC4F0)
    val liveBg get() = p(0xFFE6F4EA, 0xFF12301C)                 // «살아 있음»·owner 배지
    val liveFg get() = p(0xFF1E6B3A, 0xFF9BE3B3)
    val markBg get() = Color(0x5AE8B339)                          // 검색 강조
}

/** Heroicons v1 outline 한 개. */
@Composable
fun Hi(name: String, color: Color = H.dim, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val v = strokeIcon("hero:$name", HERO_PATHS[name]) ?: return
    Icon(v, null, tint = color, modifier = modifier.size(size))
}

/** 폴더 색은 데이터(«#C0392B»)에서 온다. 못 읽으면 회색. */
fun hex(s: String?): Color = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(H.muted)
