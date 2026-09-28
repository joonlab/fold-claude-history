package kr.joonlab.cchistory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.joonlab.core.MarkdownText
import kr.joonlab.core.PinchBadge
import kr.joonlab.core.Pretendard
import kr.joonlab.core.ScaledText
import kr.joonlab.core.TextSizeButton
import kr.joonlab.core.pinchTextScale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class CRow {
    abstract val key: String
    data class One(val m: Msg, val head: Boolean, override val key: String) : CRow()
    data class Tools(val items: List<Msg>, override val key: String) : CRow()
}

/** 연속 도구는 한 줄로 접고, Claude 가 이어서 말하는 동안은 머리(아바타·이름)를 한 번만. */
fun rowsOf(items: List<Msg>): List<CRow> {
    val out = mutableListOf<CRow>()
    var run = mutableListOf<Msg>()
    var claudeTurn = false
    fun flush() { if (run.isNotEmpty()) { out.add(CRow.Tools(run, "t-" + (run.first().id ?: out.size.toString()))); run = mutableListOf() } }
    items.forEachIndexed { i, m ->
        if (m.kind == "tool") { run.add(m); return@forEachIndexed }
        flush()
        val head = when (m.kind) {
            "assistant", "question" -> !claudeTurn
            else -> true
        }
        claudeTurn = m.kind == "assistant" || m.kind == "question" || (claudeTurn && m.kind == "tool")
        if (m.kind == "user" || m.kind == "divider") claudeTurn = false
        out.add(CRow.One(m, head, (m.id ?: "i$i") + ":" + m.kind + ":" + i))
    }
    flush()
    return out
}

private fun rowText(r: CRow) = when (r) {
    is CRow.One -> r.m.text ?: ""
    is CRow.Tools -> r.items.joinToString("\n") { (it.name ?: "") + " " + (it.summary ?: "") }
}

/**
 * 대화 칸 — 머리(제목·메타·태그·설명·폴더·살아 있음) + 뒤에서부터 페이지로 읽는 대화.
 * cover = 커버 화면(아래 막대가 북마크·찾기·맨 아래를 맡으므로 머리는 짧게).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvPane(st: AppState, sid: String, active: Boolean, cover: Boolean, onBack: (() -> Unit)?, onClose: (() -> Unit)?,
             compact: Boolean = false) {
    val s = st.sess(sid)
    val cv = remember(sid) { st.conv(sid) }
    val scope = rememberCoroutineScope()
    val live = st.live[sid]
    val lctx = LocalContext.current
    var autoScrolling by remember(sid) { mutableStateOf(false) }

    // 항목이 막 바뀐 직후엔 layoutInfo 가 옛 값이다(열자마자 맨 아래로 못 가던 원인) — 한 프레임씩 기다리며 끝에 닿을 때까지
    suspend fun toBottom() {
        autoScrolling = true
        try {
            repeat(6) {
                withFrameNanos { }
                val n = cv.list.layoutInfo.totalItemsCount
                if (n > 0) cv.list.scrollToItem(n - 1, 100_000)
                if (!cv.list.canScrollForward) return
            }
        } finally { autoScrolling = false }
    }

    LaunchedEffect(sid) {
        val first = !cv.loaded
        cv.open(st.source)
        if (first) toBottom()
    }
    // 사람이 위로 올리면 따라가기를 멈추고, 맨 아래로 내리면 다시 따라간다
    LaunchedEffect(sid) {
        snapshotFlow { cv.list.isScrollInProgress to cv.list.canScrollForward }
            .collect { (moving, fwd) -> if (moving && !autoScrolling) { cv.follow = !fwd; if (!fwd) cv.unseen = 0 } }
    }
    // 실시간 따라보기 — 살아 있는 세션만 4초마다 꼬리를 다시 받는다
    LaunchedEffect(sid, live != null, active) {
        while (active && live != null) {
            delay(4000)
            if (!cv.loaded) continue
            val n = cv.poll()
            if (cv.follow) { if (n > 0) toBottom() } else cv.unseen += n
        }
    }

    val rows = remember(cv.items, st.showTools, st.showSys) {
        rowsOf(cv.items.filter { (st.showTools || it.kind != "tool") && (st.showSys || (it.kind != "system" && it.kind != "command")) })
    }
    val q = cv.find?.trim()?.lowercase().orEmpty()
    val matches = remember(rows, q) { if (q.isEmpty()) emptyList() else rows.indices.filter { q in rowText(rows[it]).lowercase() } }
    SideEffect { cv.findN = matches.size }
    LaunchedEffect(sid, cv.findJump) {
        if (cv.findJump == cv.findDone) return@LaunchedEffect
        cv.findDone = cv.findJump
        if (matches.isEmpty()) return@LaunchedEffect
        cv.follow = false
        cv.list.animateScrollToItem(matches[cv.findAt.coerceIn(0, matches.size - 1)] + 1)
    }

    Column(Modifier.fillMaxSize().background(H.bg)) {
        // ── 머리 ── 테이블톱 위 칸(compact)은 한 줄 — 찾기·북마크·따라보기는 아래 조작판이 맡는다(목업 ⑤)
        if (compact) Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s?.title ?: sid, color = H.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (live != null) LiveLink(st, live, "${live.machine.label} · ${live.statusLabel}")
            Text("%,d messages".format(s?.messageCount ?: 0), color = H.dim, fontSize = 11.5.sp)
            TextSizeButton()
        } else Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) IconBtn("chevron-left", "목록으로", size = 44.dp, onClick = onBack)
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(s?.title ?: sid, color = H.text, fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = if (cover) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull("%,d messages".format(s?.messageCount ?: 0), s?.project, s?.let { size(it.fileSize) },
                        cv.machine?.let { "${it.label}에서 읽음" }).joinToString(" · "),
                        color = H.dim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextSizeButton()
                if (onClose != null) IconBtn("x", "나란히 닫기", onClick = onClose)
            }
            if (s != null && (s.name != null || s.tags.isNotEmpty())) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), maxLines = 2) {
                if (s.name != null) TagChip("named", named = true)
                s.tags.forEach { TagChip(it) }
            }
            if (s != null) MetaBlock(st, s, collapsed = cover)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live != null) LiveLink(st, live, "${live.machine.label} cmux · ${live.statusLabel}")
                else if (s?.owner != null) Text("⏳ ${hostLabel(s.owner)} 진행 중", color = H.gold, fontSize = 11.5.sp)
                // 이어가기 — 관제실 앱의 맥 고르는 시트로(D7). 다른 맥에서 도는지는 관제실·에이전트가 막는다
                if (st.ctl && live == null) Chip("이어가기", icon = "play", h = 32.dp) { Ctl.resume(lctx, sid, s?.name) }
                Spacer(Modifier.weight(1f))
                Chip("도구", on = st.showTools, h = 32.dp) { st.toggleTools() }
                Chip("시스템", on = st.showSys, h = 32.dp) { st.toggleSys() }
                if (!cover) {
                    IconBtn("search", "대화 찾기", on = cv.find != null, size = 36.dp) { cv.find = if (cv.find == null) "" else null }
                    if (s != null) IconBtn("bookmark", "북마크", on = s.bookmarked, size = 36.dp) { scope.launch { st.toggleBookmark(sid) } }
                }
            }
            if (cv.find != null) FindBar(cv.find!!, { cv.find = it; cv.findAt = 0 }, matches.size, cv.findAt,
                onPrev = { cv.jump(cv.findAt - 1) }, onNext = { cv.jump(cv.findAt + 1) }, onClose = { cv.find = null })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(H.border))

        // ── 대화 ──
        val ctx = LocalContext.current
        Box(Modifier.weight(1f).fillMaxWidth().background(H.panel).pinchTextScale(ctx)) {
            ScaledText {
                LazyColumn(Modifier.fillMaxSize(), state = cv.list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "top") { TopLoader(cv) { scope.launch { cv.older() } } }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        ChatRow(r, current = matches.isNotEmpty() && matches[cv.findAt.coerceIn(0, matches.size - 1)] == i)
                    }
                    item(key = "bottom") { Spacer(Modifier.size(if (cover) 64.dp else 12.dp)) }
                }
            }
            cv.err?.let {
                Text(it, color = H.danger, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                    .background(H.bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (!cv.loaded && cv.err == null) Text("대화를 읽는 중…", color = H.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            FollowPill(cv, live != null, Modifier.align(Alignment.BottomCenter).padding(bottom = if (cover) 70.dp else 14.dp)) {
                cv.follow = true; cv.unseen = 0
                scope.launch { cv.list.animateScrollToItem((cv.list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            }
            PinchBadge(Modifier.align(Alignment.Center))
        }
    }
}

/** 설명 · 폴더. 커버에서는 한 줄로 접어 두고 누르면 펼친다(목업 ③). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaBlock(st: AppState, s: Sess, collapsed: Boolean) {
    val folders = s.folders.mapNotNull { st.index?.folders?.get(it) }
    if (s.description == null && folders.isEmpty()) return
    var open by remember(s.sid) { mutableStateOf(!collapsed) }
    val accent = H.accent
    if (!open) {
        Text("▸ " + listOfNotNull(s.description?.let { "설명" }, folders.takeIf { it.isNotEmpty() }?.let { "폴더 ${it.size}" }).joinToString(" · "),
            color = H.dim, fontSize = 12.5.sp, modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp))
        return
    }
    var full by remember(s.sid) { mutableStateOf(false) }
    s.description?.let { d ->
        Text(d, color = H.text, fontSize = 12.5.sp, lineHeight = 19.sp, maxLines = if (full) 40 else 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(H.panel, RoundedCornerShape(8.dp))
                .drawBehind { drawRect(accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                .clickable { full = !full }.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp))
    }
    if (folders.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        folders.forEach { f ->
            Row(Modifier.border(1.dp, H.border, RoundedCornerShape(12.dp)).clickable { st.folder = f.id }
                .padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Dot(hex(f.color), 7.dp)
                Text("${f.icon ?: "📁"} ${f.name}", color = H.text, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
    if (collapsed) Text("▴ 접기", color = H.dim, fontSize = 12.sp, modifier = Modifier.clickable { open = false })
}

@Composable
fun FindBar(v: String, onChange: (String) -> Unit, n: Int, at: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).background(H.panel2, RoundedCornerShape(10.dp)).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Hi("search", H.dim, 15.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (v.isEmpty()) Text("불러온 대화에서 찾기", color = H.muted, fontSize = 15.sp)
            BasicTextField(v, onChange, singleLine = true, cursorBrush = SolidColor(H.accent),
                textStyle = TextStyle(color = H.text, fontSize = 16.sp, fontFamily = Pretendard), modifier = Modifier.fillMaxWidth())
        }
        Text(if (n == 0) "0" else "${at + 1}/$n", color = H.dim, fontSize = 12.sp)
        Box(Modifier.size(38.dp).clickable(onClick = onPrev), contentAlignment = Alignment.Center) { Hi("arrow-up", H.text, 16.dp) }
        Box(Modifier.size(38.dp).clickable(onClick = onNext), contentAlignment = Alignment.Center) { Hi("arrow-down", H.text, 16.dp) }
        Box(Modifier.size(38.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Hi("x", H.dim, 16.dp) }
    }
}

@Composable
private fun TopLoader(cv: Conv, onOlder: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        when {
            !cv.loaded -> {}
            cv.atStart -> Text("대화의 처음입니다", color = H.dim, fontSize = 12.sp)
            else -> Text(when {
                    cv.loadingOlder -> "불러오는 중…"
                    cv.before == null && cv.chainLeft > 0 -> "↑ 이어진 앞 파일 ${cv.chainLeft}개 — 불러오기"
                    else -> "↑ 이전 대화 불러오기"
                }, color = H.dim, fontSize = 12.sp,
                modifier = Modifier.background(H.tertiary, RoundedCornerShape(12.dp)).clickable(enabled = !cv.loadingOlder, onClick = onOlder)
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun FollowPill(cv: Conv, live: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val label = when {
        !cv.follow && cv.unseen > 0 -> "새 메시지 ${cv.unseen} ↓"
        !cv.follow -> "맨 아래 ↓"
        live -> "실시간 따라보기 켜짐"
        else -> return
    }
    Row(modifier.height(36.dp).background(H.text, RoundedCornerShape(18.dp)).clickable(onClick = onTap).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) Hi("eye", H.bg, 15.dp)
        Text(label, color = H.bg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm")
fun msgTime(ts: Double?): String {
    if (ts == null) return ""
    val t = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault())
    return t.format(if (t.toLocalDate() == LocalDate.now()) HM else MDHM)
}

@Composable
fun ChatRow(r: CRow, current: Boolean) {
    val ring = if (current) Modifier.border(2.dp, androidx.compose.ui.graphics.Color(0xFFF97316), RoundedCornerShape(14.dp)) else Modifier
    when (r) {
        is CRow.Tools -> ToolGroup(r.items, ring)
        is CRow.One -> {
            val m = r.m
            when (m.kind) {
                "user" -> Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(buildLabel("User", H.accent, msgTime(m.ts)), fontSize = 11.sp)
                    SelectionContainer {
                        Text(m.text ?: "", color = H.onAccent, fontSize = 14.sp, lineHeight = 22.sp,
                            modifier = ring.widthIn(max = 560.dp).background(H.accent, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                                .padding(horizontal = 13.dp, vertical = 10.dp))
                    }
                }
                "assistant" -> Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.head) Box(Modifier.size(26.dp).background(H.gold, CircleShape), contentAlignment = Alignment.Center) {
                        Text("C", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else Spacer(Modifier.width(26.dp))
                    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (r.head) Text(buildLabel("Claude", H.gold, msgTime(m.ts)), fontSize = 11.sp)
                        SelectionContainer {
                            MarkdownText(m.text ?: "", modifier = ring.widthIn(max = 720.dp)
                                .background(H.bg, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                                .border(1.dp, H.border, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                                .padding(horizontal = 13.dp, vertical = 10.dp))
                        }
                    }
                }
                "question" -> QuestionCard(m, ring)
                "divider" -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(1.dp).background(H.border))
                    Text(m.text ?: "", color = H.dim, fontSize = 11.5.sp)
                    Box(Modifier.weight(1f).height(1.dp).background(H.border))
                }
                else -> SysChip(m, ring)   // system · command
            }
        }
    }
}

private fun buildLabel(who: String, color: androidx.compose.ui.graphics.Color, time: String) =
    androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.SemiBold)); append(who); pop()
        if (time.isNotEmpty()) { pushStyle(androidx.compose.ui.text.SpanStyle(color = H.dim)); append(" · $time"); pop() }
    }

@Composable
private fun ToolGroup(tools: List<Msg>, ring: Modifier) {
    var open by remember { mutableStateOf(false) }
    val names = tools.groupingBy { it.name ?: "?" }.eachCount().entries.joinToString(" · ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    val pending = tools.any { it.status == "pending" }
    Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 20.dp).then(ring)
        .background(H.bg, RoundedCornerShape(8.dp)).border(1.dp, if (pending) H.accent else H.border, RoundedCornerShape(8.dp))
        .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Hi(if (open) "chevron-down" else "chevron-right", H.dim, 12.dp)
            Text("도구 ${tools.size}개 · $names" + (if (pending) " · 실행 중" else ""), color = if (pending) H.liveFg else H.dim,
                fontSize = 12.sp, maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) tools.forEach { t ->
            val mark = when (t.status) { "done" -> "✓"; "error" -> "✗"; "pending" -> "…"; else -> "?" }
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$mark ${t.name}", color = if (t.status == "error") H.danger else H.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                SelectionContainer { Text(t.summary ?: "", color = H.dim, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 4,
                    overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun QuestionCard(m: Msg, ring: Modifier) {
    val qs = m.questions
    Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 20.dp).then(ring)
        .background(H.goldTint, RoundedCornerShape(12.dp)).border(1.dp, H.goldLine, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(when (m.status) { "done" -> "질문 · 답함"; "pending" -> "질문 · 답 대기"; else -> "질문" }, color = H.goldText,
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (qs != null) for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            Text(q.optString("question"), color = H.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
            val opts = q.optJSONArray("options")
            if (opts != null) for (j in 0 until opts.length()) {
                Text("${j + 1}. ${opts.getJSONObject(j).optString("label")}", color = H.goldText, fontSize = 12.5.sp)
            }
        }
        if (m.answer != null) Text("→ ${m.answer}", color = H.goldText, fontSize = 12.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SysChip(m: Msg, ring: Modifier) {
    var open by remember { mutableStateOf(false) }
    val line = H.borderHover
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(ring)
        .drawBehind {
            drawRoundRect(line, cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        }
        .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (m.kind == "command") "⌘" else "📌", fontSize = 11.sp)
        Text((m.text ?: "") + (if (m.ts != null) "  · ${msgTime(m.ts)}" else ""), color = H.dim, fontSize = 12.sp,
            maxLines = if (open) 30 else 1, overflow = TextOverflow.Ellipsis)
    }
}

