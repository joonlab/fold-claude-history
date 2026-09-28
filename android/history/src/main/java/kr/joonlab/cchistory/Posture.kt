package kr.joonlab.cchistory

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 반접힘 힌지 — FoldingFeature 를 앱이 쓰는 모양으로. 좌표는 **창 기준 px**.
 * horizontal = 힌지가 가로로 누움(테이블톱: 위·아래) · 아니면 세로(책: 왼쪽·오른쪽).
 * 완전히 펼친(FLAT) 힌지는 null — 그때는 폭으로만 가른다(M3 배치 그대로).
 */
data class Hinge(val horizontal: Boolean, val start: Float, val end: Float)

@Composable
fun rememberHinge(): Hinge? {
    val act = LocalContext.current as? Activity ?: return null
    var feat by remember { mutableStateOf<FoldingFeature?>(null) }
    var angle by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(act) {
        WindowInfoTracker.getOrCreate(act).windowLayoutInfo(act).collect { info ->
            android.util.Log.i("cchistory", "layout: " + info.displayFeatures.joinToString { f ->
                if (f is FoldingFeature) "fold(${f.state} ${f.orientation} sep=${f.isSeparating} ${f.bounds})" else f.toString() })
            feat = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        }
    }
    // 폴드8(One UI) 은 반쯤 접어도 FoldingFeature 를 FLAT 으로만 준다 — 기기 상태가 TENT(1)로 바뀌어도 확장이 알리지 않는다
    // (HALF_OPENED(2) 는 app_accessible=false, 실측 2026-09-25). 힌지 위치·방향은 FLAT 에도 정확히 오므로
    // 접힌 정도만 공개 센서 TYPE_HINGE_ANGLE 로 따로 읽는다
    DisposableEffect(act) {
        val sm = act.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        var logged = -1
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values[0]
                angle = v
                val bucket = (v / 10).toInt()
                if (bucket != logged) { logged = bucket; android.util.Log.i("cchistory", "hinge angle=%.1f".format(v)) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        android.util.Log.i("cchistory", "hinge sensor=${sensor?.name ?: "없음"}")
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(l) }
    }
    // 접거나 펼 때 90° 를 스쳐 지나간다(실측: 커버로 접는 중 book 0.1~0.5초 번쩍) → 반접힘은 0.5초 머물러야 인정, 벗어나는 건 즉시
    val raw = feat?.state == FoldingFeature.State.HALF_OPENED || angle?.let { it in HALF_MIN..HALF_MAX } == true
    var half by remember { mutableStateOf(false) }
    LaunchedEffect(raw) { if (raw) { delay(HALF_SETTLE_MS); half = true } else half = false }
    val f = feat ?: return null
    if (!half) return null
    val h = f.orientation == FoldingFeature.Orientation.HORIZONTAL
    val b = f.bounds
    return if (h) Hinge(true, b.top.toFloat(), b.bottom.toFloat()) else Hinge(false, b.left.toFloat(), b.right.toFloat())
}

/** 반접힘으로 볼 힌지 각도(도) — 안드로이드 FoldingFeature 의 HALF_OPENED 와 같은 뜻: 닫힘·완전히 펼침 사이 */
private const val HALF_MIN = 30f
private const val HALF_MAX = 150f
private const val HALF_SETTLE_MS = 500L

/** 힌지 띠 — 이 폭(또는 높이) 안에는 글자를 놓지 않는다. 힌지가 0px 선으로 오면 이 값으로 벌린다. */
private val BAND = 24.dp

/** 창 기준 힌지를 이 칸 안의 dp 로 — origin = 칸의 창 좌표(px). 띠의 시작·끝을 돌려준다. */
@Composable
private fun bandIn(h: Hinge, origin: Float): Pair<Dp, Dp> {
    val d = LocalDensity.current
    val a = with(d) { (h.start - origin).toDp() }
    val b = with(d) { (h.end - origin).toDp() }
    val mid = (a + b) / 2
    val half = maxOf(b - a, BAND) / 2
    return (mid - half) to (mid + half)
}

@Composable
private fun HingeBand(modifier: Modifier, horizontal: Boolean) {
    val line = H.border
    Box(modifier.background(H.panel2).drawBehind {
        val t = 1.dp.toPx()
        if (horizontal) {
            drawRect(line, size = androidx.compose.ui.geometry.Size(size.width, t))
            drawRect(line, topLeft = Offset(0f, size.height - t), size = androidx.compose.ui.geometry.Size(size.width, t))
        } else {
            drawRect(line, size = androidx.compose.ui.geometry.Size(t, size.height))
            drawRect(line, topLeft = Offset(size.width - t, 0f), size = androidx.compose.ui.geometry.Size(t, size.height))
        }
    })
}

// ───────────── 반접힘 테이블톱 — 위: 대화 읽기 / 힌지 / 아래: 조작판(목업 ⑤) ─────────────

@Composable
fun Tabletop(st: AppState, active: Boolean, h: Hinge, originY: Float) {
    val sid = st.sel
    if (st.tableFolded) {
        // 아래 칸을 접으면 위 칸이 화면 전체 — 힌지는 하드웨어라 끌 수 없으니 칸 자체를 접는다(DESIGN §4)
        Box(Modifier.fillMaxSize()) {
            if (sid != null) ConvPane(st, sid, active, cover = false, onBack = null, onClose = null)
            else Empty("세션을 고르려면 조작판을 펼치세요")
            Pill("조작판 펼치기", "chevron-up", Modifier.align(Alignment.BottomEnd).padding(14.dp)) { st.tableFolded = false }
        }
        return
    }
    val (top, bottom) = bandIn(h, originY)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(top)) {
            if (sid != null) ConvPane(st, sid, active, cover = false, onBack = null, onClose = null, compact = true)
            else Empty("아래에서 세션을 고르세요")
        }
        HingeBand(Modifier.fillMaxWidth().height(bottom - top), horizontal = true)
        Controls(st, active, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun Controls(st: AppState, active: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sid = st.sel
    val cv = sid?.let { remember(it) { st.conv(it) } }
    val s = st.sess(sid)
    val items = st.rows().filterIsInstance<Row.Item>()
    Column(modifier.background(H.bg).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (cv != null) FindBar(cv.find ?: "", { cv.find = it; cv.findAt = 0 }, cv.findN, cv.findAt,
                    onPrev = { cv.jump(cv.findAt - 1) }, onNext = { cv.jump(cv.findAt + 1) }, onClose = { cv.find = null })
                else SearchField(st, big = false)
            }
            if (cv != null) {
                Chip(if (cv.follow) "따라보기" else "맨 아래", on = cv.follow, icon = "eye", h = 42.dp) {
                    cv.follow = true; cv.unseen = 0
                    scope.launch { cv.list.animateScrollToItem((cv.list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                }
                if (s != null) IconBtn("bookmark", "북마크", on = s.bookmarked, size = 42.dp) { scope.launch { st.toggleBookmark(s.sid) } }
                // 목업 ⑤ 의 «관제실» 자리 — 살아 있으면 그 탭으로, 아니면 이어가기(맥 고르기는 관제실이)
                if (s != null && st.ctl) {
                    val lv = st.live[s.sid]
                    Chip(if (lv != null) "관제실" else "이어가기", icon = if (lv != null) "terminal" else "play", h = 42.dp) {
                        if (lv != null) Ctl.openTab(ctx, lv) else Ctl.resume(ctx, s.sid, s.name)
                    }
                }
            }
            IconBtn("chevron-down", "조작판 접기", size = 42.dp) { st.tableFolded = true }
        }
        val f = st.folder?.let { st.index?.folders?.get(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((f?.let { "${it.icon ?: "📁"} ${it.name}" } ?: st.query.trim().takeIf { it.isNotEmpty() }?.let { "«$it» 일치" }
                ?: if (st.filtered) "거른 목록" else "모든 세션") +
                " · ${items.size} — 옆으로 넘겨 다른 세션", color = H.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // 대화를 보는 중엔 찾기 칸이 대화 찾기다 — 목록 거르기는 시트로
            Chip("폴더", on = st.folder != null, icon = "folder") { st.sheet = "folders" }
            Chip("태그", on = st.tags.isNotEmpty(), icon = "tag") { st.sheet = "tags" }
            if (st.filtered) Chip("모두", icon = "x") { st.clearFilters() }
        }
        // 고른 세션이 화면 밖이면 보이게 — 칸이 다시 들어올 때도 같은 자리로
        LaunchedEffect(sid, items.size) {
            val i = items.indexOfFirst { it.s.sid == sid }
            if (i >= 0 && st.tableRow.layoutInfo.visibleItemsInfo.none { it.index == i }) st.tableRow.animateScrollToItem(i)
        }
        LazyRow(Modifier.fillMaxWidth().weight(1f), state = st.tableRow, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.s.sid }) { r -> Card(st, r.s, r.s.sid == sid) { st.sel = r.s.sid } }
        }
    }
}

@Composable
private fun Card(st: AppState, s: Sess, selected: Boolean, onClick: () -> Unit) {
    val live = st.live[s.sid]
    Column(Modifier.width(212.dp).fillMaxHeight()
        .background(if (selected) H.accentTint else H.bg, RoundedCornerShape(12.dp))
        .border(1.dp, if (selected) H.accent else H.border, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live != null) Dot(H.liveFg, 7.dp)
            Text(shortTime(s.at), color = H.dim, fontSize = 11.sp)
            Box(Modifier.weight(1f))
            Text("%,d".format(s.messageCount), color = H.dim, fontSize = 10.5.sp)
        }
        Text(s.title, color = H.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (s.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { s.tags.take(3).forEach { TagChip(it) } }
        Box(Modifier.weight(1f))
        s.folders.firstOrNull()?.let { st.index?.folders?.get(it) }?.let { f ->
            Text("${f.icon ?: "📁"} ${f.name}" + if (s.folders.size > 1) " 외 ${s.folders.size - 1}" else "",
                color = H.dim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (live != null) Text("${live.machine.label} · ${live.statusLabel}", color = H.liveFg, fontSize = 11.5.sp, maxLines = 1)
    }
}

// ───────────── 반접힘 책 — 힌지를 칸 경계로: 왼쪽 목록 | 힌지 | 오른쪽 대화 ─────────────

@Composable
fun Book(st: AppState, active: Boolean, h: Hinge, originX: Float, list: @Composable () -> Unit) {
    val (l, r) = bandIn(h, originX)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(l).fillMaxHeight()) { list() }
        HingeBand(Modifier.width(r - l).fillMaxHeight(), horizontal = false)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val sid = st.sel
            if (sid != null) ConvPane(st, sid, active, cover = false, onBack = null, onClose = null)
            else Empty("왼쪽 목록에서 세션을 고르세요")
        }
    }
}

@Composable
private fun Empty(msg: String) {
    Box(Modifier.fillMaxSize().background(H.panel), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hi("chat", H.muted, 36.dp)
            Text(msg, color = H.dim, fontSize = 14.sp)
            Text("빌드 ${BuildConfig.BUILD_TIME}", color = H.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Pill(label: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(40.dp).background(H.text, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Hi(icon, H.bg, 15.dp)
        Text(label, color = H.bg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
