package kr.joonlab.cchistory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kr.joonlab.core.Pretendard
import kr.joonlab.core.ThemeMode

// ───────────── 작은 부품 ─────────────

@Composable
fun Chip(label: String, on: Boolean = false, icon: String? = null, h: Dp = 34.dp, onClick: () -> Unit) {
    Row(Modifier.height(h).background(if (on) H.accentTint else H.bg, RoundedCornerShape(h / 2))
        .border(1.dp, if (on) H.accent else H.border, RoundedCornerShape(h / 2))
        .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (icon != null) Hi(icon, if (on) H.accent else H.dim, 14.dp)
        Text(label, color = if (on) H.accent else H.text, fontSize = 12.5.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
fun TagChip(t: String, named: Boolean = false) {
    Text(t, color = if (named) H.goldText else H.tagFg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(if (named) H.goldTint else H.tagBg, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun LiveBadge(text: String, onClick: (() -> Unit)? = null) {
    Text("● $text" + if (onClick != null) " ›" else "", color = H.liveFg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(H.liveBg, RoundedCornerShape(5.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = if (onClick != null) 4.dp else 2.dp))
}

/** 살아 있음 배지 — 누르면 관제실의 그 탭 대화로(D7 ①). 관제실 앱이 없으면 그냥 배지. */
@Composable
fun LiveLink(st: AppState, live: LiveRow, text: String) {
    val ctx = LocalContext.current
    LiveBadge(text, onClick = if (st.ctl) ({ Ctl.openTab(ctx, live) }) else null)
}

@Composable
fun IconBtn(icon: String, desc: String, on: Boolean = false, size: Dp = 40.dp, tint: Color? = null, onClick: () -> Unit) {
    Box(Modifier.size(size).background(if (on) H.accentTint else H.panel2, RoundedCornerShape(10.dp))
        .border(1.dp, if (on) H.accent else H.panel2, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Hi(icon, tint ?: if (on) H.accent else H.text, 18.dp)
    }
}

fun hostLabel(k: String?) = machineOf(k)?.label ?: k ?: "?"

// ───────────── 머리 ─────────────

@Composable
fun Brand(st: AppState, big: Boolean) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 공개본: 브랜드 로고 이미지 대신 앱 아이콘 색의 작은 표식만 둔다
        Hi("chat", H.accent, if (big) 24.dp else 20.dp)
        Text("Claude Code", color = H.text, fontSize = if (big) 19.sp else 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1)
        IconBtn(if (ThemeMode.dark) "sun" else "moon", "테마", size = if (big) 44.dp else 36.dp) { ThemeMode.cycle(ctx) }
    }
}

/** «홈맥에서 읽음 · 인덱스 2분 전 · 실행 중 3» — 어느 맥의 몇 분 전 인덱스인지 늘 드러낸다(D2). */
@Composable
fun MachineLine(st: AppState) {
    val ix = st.index
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable { scope.launch { st.refresh() } }) {
        if (ix == null) {
            Text(if (st.loading) "목록을 받는 중…" else (st.loadErr ?: "아직 목록이 없습니다"),
                color = if (st.loadErr != null) H.danger else H.dim, fontSize = 12.sp, maxLines = 2)
            return@Row
        }
        Hi(if (ix.machine.key == "home") "home" else "desktop", if (st.loadErr != null) H.danger else H.liveFg, 14.dp)
        Text(ix.machine.label + (if (st.loadErr != null) " 캐시" else "에서 읽음"),
            color = if (st.loadErr != null) H.danger else H.liveFg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("· 인덱스 ${ago(ix.ageSec())} · cmux 열림 ${st.live.size}" + (if (st.loading) " · 받는 중…" else ""),
            color = H.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (ix != null && st.loadErr != null)
        Text("연결 안 됨 — ${st.loadErr}", color = H.danger, fontSize = 11.sp, maxLines = 2)
}

@Composable
fun SearchField(st: AppState, big: Boolean) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(if (big) 46.dp else 40.dp).background(H.panel2, RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Hi("search", H.dim, 17.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (st.query.isEmpty()) Text("세션 검색… (엔터 = 본문 ${modeLabel(st.mode)})", color = H.muted, fontSize = 15.sp, maxLines = 1)
            BasicTextField(st.query, { v -> st.query = v; if (v.trim() != st.hitsFor) { st.hits = null; st.searchErr = null } },
                singleLine = true, cursorBrush = SolidColor(H.accent),
                textStyle = TextStyle(color = H.text, fontSize = 16.sp, fontFamily = Pretendard),   // 16 미만이면 폰이 확대한다
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); scope.launch { st.search(st.query) } }),
                modifier = Modifier.fillMaxWidth())
        }
        if (st.searching) Text("찾는 중", color = H.dim, fontSize = 11.sp)
        if (st.query.isNotEmpty()) Box(Modifier.size(36.dp).clickable { st.query = ""; st.hits = null; st.hitsFor = ""; st.searchErr = null },
            contentAlignment = Alignment.Center) { Hi("x", H.dim, 16.dp) }
    }
    st.searchErr?.let { Text(it, color = H.danger, fontSize = 11.5.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChips(st: AppState, folderChip: Boolean, wrap: Boolean) {
    val scope = rememberCoroutineScope()
    val content: @Composable () -> Unit = {
        if (folderChip) {
            val f = st.folder?.let { st.index?.folders?.get(it) }
            Chip(if (f != null) "${f.icon ?: "📁"} ${f.name} ✕" else "폴더", on = f != null, icon = if (f == null) "folder" else null) {
                if (f != null) st.folder = null else st.sheet = "folders"
            }
        }
        Chip(dateLabel(st.date), on = st.date != "all", icon = "calendar") {
            st.date = when (st.date) { "all" -> "today"; "today" -> "7d"; "7d" -> "30d"; else -> "all" }
        }
        Chip(if (st.tags.isEmpty()) "태그" else "태그 ${st.tags.size}", on = st.tags.isNotEmpty(), icon = "tag") { st.sheet = "tags" }
        Chip("북마크", on = st.onlyMarked, icon = "bookmark") { st.onlyMarked = !st.onlyMarked }
        Chip("cmux 열림", on = st.onlyLive) { st.onlyLive = !st.onlyLive }
        Chip(modeLabel(st.mode), icon = "search") {
            st.mode = when (st.mode) { "hybrid" -> "keyword"; "keyword" -> "semantic"; else -> "hybrid" }
            if (st.hits != null) scope.launch { st.search(st.hitsFor) }
        }
        if (st.filtered) Chip("초기화", icon = "x") { st.clearFilters() }
    }
    if (wrap) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

// ───────────── 폴더 트리 ─────────────

@Composable
fun FolderTree(st: AppState, onPick: () -> Unit = {}) {
    val ix = st.index ?: return
    val kids = remember(ix) { ix.folders.values.groupBy { f -> f.parent?.takeIf { it in ix.folders } }
        .mapValues { (_, v) -> v.sortedBy { it.name } } }
    val rows = buildList {
        fun walk(p: String?, depth: Int) {
            for (f in kids[p].orEmpty()) {
                add(f to depth)
                if (f.id in st.openFolders) walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FolderRow("🗂️", "모든 세션", ix.items.size, null, 0, selected = st.folder == null, hasKids = false, open = false,
            onToggle = {}) { st.folder = null; onPick() }
        for ((f, depth) in rows) {
            FolderRow(f.icon ?: "📁", f.name, f.count, hex(f.color), depth, selected = st.folder == f.id,
                hasKids = kids[f.id].orEmpty().isNotEmpty(), open = f.id in st.openFolders,
                onToggle = { st.openFolders = if (f.id in st.openFolders) st.openFolders - f.id else st.openFolders + f.id }) {
                st.folder = if (st.folder == f.id) null else f.id
                onPick()
            }
        }
    }
}

@Composable
private fun FolderRow(icon: String, name: String, n: Int, color: Color?, depth: Int, selected: Boolean, hasKids: Boolean,
                      open: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).background(if (selected) H.accentTint else Color.Transparent, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(start = (4 + depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(26.dp).clickable(enabled = hasKids, onClick = onToggle), contentAlignment = Alignment.Center) {
            if (hasKids) Hi(if (open) "chevron-down" else "chevron-right", H.dim, 13.dp)
        }
        if (color != null) Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(icon, fontSize = 13.sp)
        Text(name, color = if (selected) H.accent else H.text, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Text("$n", color = H.dim, fontSize = 11.sp, modifier = Modifier.background(H.tertiary, RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp))
    }
}

// ───────────── 세션 목록 ─────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionList(st: AppState, modifier: Modifier = Modifier, onOpen: (String) -> Unit, onOpen2: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val rows = st.rows()
    PullToRefreshBox(isRefreshing = st.loading, onRefresh = { scope.launch { st.refresh() } }, modifier = modifier) {
        if (st.index == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (st.loading) "목록을 받는 중…" else (st.loadErr ?: "목록이 없습니다"), color = H.dim, fontSize = 13.sp)
                if (!st.loading) Chip("다시 받기", icon = "refresh") { scope.launch { st.refresh() } }
            }
            return@PullToRefreshBox
        }
        LazyColumn(Modifier.fillMaxSize(), state = st.listScroll) {
            if (rows.isEmpty()) item {
                Text("맞는 세션이 없습니다", color = H.dim, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
            }
            rows.forEachIndexed { i, r ->
                when (r) {
                    is Row.Head -> stickyHeader(key = "h$i-${r.label}") {
                        Text("${r.label} (${r.count})", color = H.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().background(H.panel2).padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    is Row.Item -> item(key = r.s.sid) {
                        SessionRow(st, r.s, r.snippet, selected = r.s.sid == st.sel || r.s.sid == st.sel2,
                            onClick = { onOpen(r.s.sid) }, onLong = onOpen2?.let { f -> { f(r.s.sid) } })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionRow(st: AppState, s: Sess, snippet: String?, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val live = st.live[s.sid]
    val accent = H.accent
    Column(Modifier.fillMaxWidth().background(if (selected) H.accentTint else H.bg)
        .drawBehind {
            if (selected) drawRect(accent, size = Size(3.dp.toPx(), size.height))
            drawRect(H.panel2, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        }
        .combinedClickable(onClick = onClick, onLongClick = onLong)
        .padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live != null) LiveLink(st, live, "${live.machine.label} · ${live.statusLabel}")
            else if (s.owner != null) Text("⏳ ${hostLabel(s.owner)}", color = H.gold, fontSize = 11.sp)
            if (s.type == "ghost") Text("👻", fontSize = 11.sp)
            Text(shortTime(s.at), color = H.dim, fontSize = 11.5.sp)
            Spacer(Modifier.weight(1f))
            if (s.bookmarked) Hi("bookmark", H.accent, 14.dp)
            Text("%,d".format(s.messageCount), color = H.dim, fontSize = 11.sp,
                modifier = Modifier.background(H.panel2, RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        Text(s.title, color = H.text, fontSize = 14.sp, lineHeight = 19.sp,
            fontWeight = if (s.name != null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!snippet.isNullOrBlank()) Text(markSnippet(snippet, st.hitsFor), color = H.dim, fontSize = 12.sp, lineHeight = 17.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawBehind { drawRect(Color(0xFFE8B339), size = Size(2.dp.toPx(), size.height)) }.padding(start = 8.dp))
        if (s.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = 1) {
            s.tags.take(5).forEach { TagChip(it) }
        }
        val f = s.folders.firstNotNullOfOrNull { st.index?.folders?.get(it) }
        if (f != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Hi("folder", H.accent, 12.dp)
            Text(f.name + if (s.folders.size > 1) " 외 ${s.folders.size - 1}" else "", color = H.accent, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** 스니펫의 ⟪…⟫(서버 강조) 또는 검색어를 형광으로. */
fun markSnippet(s: String, q: String): AnnotatedString = buildAnnotatedString {
    val src = s.replace("\n", " ")
    val hl = SpanStyle(background = H.markBg, color = H.text)
    if (src.contains('⟪')) {
        var i = 0
        while (i < src.length) {
            val a = src.indexOf('⟪', i)
            if (a < 0) { append(src.substring(i)); break }
            val b = src.indexOf('⟫', a)
            if (b < 0) { append(src.substring(i)); break }
            append(src.substring(i, a))
            pushStyle(hl); append(src.substring(a + 1, b)); pop()
            i = b + 1
        }
        return@buildAnnotatedString
    }
    val lo = src.lowercase()
    val qq = q.trim().lowercase()
    var i = 0
    while (qq.isNotEmpty()) {
        val a = lo.indexOf(qq, i)
        if (a < 0) break
        append(src.substring(i, a)); pushStyle(hl); append(src.substring(a, a + qq.length)); pop()
        i = a + qq.length
    }
    append(src.substring(i))
}

// ───────────── 위에서 덮는 시트(커버·세로) ─────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Sheet(st: AppState) {
    val which = st.sheet ?: return
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { st.sheet = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(H.bg, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .clickable(enabled = false) {}.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (which == "folders") "폴더" else "태그", color = H.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (which == "tags" && st.tags.isNotEmpty()) Chip("모두 해제") { st.tags = emptySet() }
                Spacer(Modifier.width(6.dp))
                IconBtn("x", "닫기") { st.sheet = null }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (which == "folders") FolderTree(st) { st.sheet = null }
                else {
                    val counts = remember(st.index) {
                        st.index?.items.orEmpty().flatMap { it.tags }.groupingBy { it }.eachCount()
                            .entries.sortedByDescending { it.value }.take(80)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        counts.forEach { (t, n) ->
                            Chip("$t $n", on = t in st.tags) { st.tags = if (t in st.tags) st.tags - t else st.tags + t }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color, d: Dp = 6.dp) = Box(Modifier.size(d).background(color, CircleShape))
