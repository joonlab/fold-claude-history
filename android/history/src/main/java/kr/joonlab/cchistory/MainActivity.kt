package kr.joonlab.cchistory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.joonlab.core.CoreTheme
import kr.joonlab.core.SplitPane
import kr.joonlab.core.WIDE
import kr.joonlab.core.rememberPaneState

class MainActivity : ComponentActivity() {
    /** 화면이 안 보이는 동안은 폴링을 쉰다(배터리). */
    private val resumed = mutableStateOf(false)
    /** 관제실이 연 세션(cchistory://session/{sid}) — 처리하면 비운다. singleTask 라 떠 있는 동안 온 링크는 onNewIntent 로 온다. */
    private val link = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) link.value = linkSid(intent?.data)
        setContent { CoreTheme(H, defaultMode = "light") { App(resumed.value, link.value) { link.value = null } } }   // 웹 뷰어 기본과 같게
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        linkSid(intent.data)?.let { link.value = it }
    }

    override fun onResume() { super.onResume(); resumed.value = true }
    override fun onPause() { resumed.value = false; super.onPause() }
}

// DESIGN §4 레일 — 14dp, 120dp 밑이면 접힘, 대화 최소 320dp
private val RAIL = 14.dp
private const val FOLD = 120f

@Composable
fun App(active: Boolean, link: String? = null, onLinkDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val st = remember { AppState(ctx.applicationContext) }
    // 관제실에서 온 세션 — 그 대화를 바로 연다(커버면 대화 화면으로, 나란히 보기는 닫는다)
    LaunchedEffect(link) {
        val sid = link ?: return@LaunchedEffect
        st.sel2 = null; st.sheet = null; st.sel = sid; st.coverDetail = true; st.tableFolded = false
        onLinkDone()
    }
    LaunchedEffect(Unit) { st.loadCache(); st.refresh() }
    LaunchedEffect(active) { while (active) { st.pollLive(); delay(5000) } }
    LaunchedEffect(st.toast) { if (st.toast != null) { delay(2600); st.toast = null } }

    BackHandler(enabled = st.sheet != null || st.sel2 != null) {
        if (st.sheet != null) st.sheet = null else st.sel2 = null
    }

    val hinge = rememberHinge()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(H.bg).safeDrawingPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
            val w = maxWidth
            // 폭으로 가르되, 반접힘이면 힌지 방향이 이긴다 — 가로 힌지 = 테이블톱, 세로 힌지 = 책
            val posture = when {
                w < WIDE -> "cover"
                hinge?.horizontal == true -> "table"
                hinge != null -> "book"
                w < 840.dp || maxWidth < maxHeight -> "port"
                else -> "land"
            }
            LaunchedEffect(posture) { st.onPosture(posture) }
            when (posture) {
                "cover" -> Cover(st, active)
                "table" -> Tabletop(st, active, hinge!!, origin.y)
                "book" -> Book(st, active, hinge!!, origin.x) {
                    Column(Modifier.fillMaxSize()) {
                        ListHead(st, big = false)
                        SessionList(st, Modifier.weight(1f), onOpen = { st.sel = it })
                    }
                }
                "port" -> Portrait(st, active, w)
                else -> Landscape(st, active, w)
            }
        }
        Sheet(st)
        st.toast?.let { (msg, bad) ->
            Text(msg, color = H.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
                    .background(if (bad) H.danger else H.text, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

/** 목록 칸 머리 — 커버·펼침 세로에서 쓴다(가로는 사이드바가 따로). */
@Composable
private fun ListHead(st: AppState, big: Boolean) {
    Column(Modifier.fillMaxWidth().background(if (big) H.bg else H.panel).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Brand(st, big)
        MachineLine(st)
        SearchField(st, big)
        FilterChips(st, folderChip = true, wrap = false)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(H.border))
}

// ───────────── 접힘(커버) — 한 화면씩 + 아래 막대 ─────────────

@Composable
private fun Cover(st: AppState, active: Boolean) {
    val scope = rememberCoroutineScope()
    val sid = st.sel
    // 커버의 뒤로 = 대화 → 목록(시트가 열려 있으면 뿌리의 BackHandler 가 먼저 닫는다 — 나중에 등록된 쪽이 이긴다)
    BackHandler(enabled = st.coverDetail && st.sheet == null) { st.coverDetail = false }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (st.coverDetail && sid != null) {
                ConvPane(st, sid, active, cover = true, onBack = { st.coverDetail = false }, onClose = null)
            } else Column(Modifier.fillMaxSize()) {
                ListHead(st, big = true)
                SessionList(st, Modifier.weight(1f), onOpen = { st.sel = it; st.coverDetail = true })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(H.border))
        Row(Modifier.fillMaxWidth().background(H.bg)) {
            if (st.coverDetail && sid != null) {
                val cv = st.conv(sid)
                val s = st.sess(sid)
                Tab("menu", "목록") { st.coverDetail = false }
                Tab("search", "대화 찾기", on = cv.find != null) { cv.find = if (cv.find == null) "" else null }
                Tab("bookmark", "북마크", on = s?.bookmarked == true) { scope.launch { st.toggleBookmark(sid) } }
                Tab("arrow-down", "맨 아래") {
                    cv.follow = true; cv.unseen = 0
                    scope.launch { cv.list.animateScrollToItem((cv.list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                }
            } else {
                Tab("menu", "목록", on = true) { scope.launch { st.listScroll.animateScrollToItem(0) } }
                Tab("folder", "폴더", on = st.folder != null) { st.sheet = "folders" }
                Tab("tag", "태그", on = st.tags.isNotEmpty()) { st.sheet = "tags" }
                Tab("arrow-up", "맨 위") { scope.launch { st.listScroll.scrollToItem(0) } }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(icon: String, label: String, on: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.weight(1f).height(60.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Hi(icon, if (on) H.accent else H.dim, 22.dp)
        Text(label, color = if (on) H.accent else H.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────── 펼침 세로 — 목록 | 대화 ─────────────

@Composable
private fun Portrait(st: AppState, active: Boolean, w: Dp) {
    val pane = rememberPaneState("list-port", 300f)
    SplitPane(pane, w, railW = RAIL, minW = 220f, foldBelow = FOLD, minRest = 320.dp,
        pane = {
            Column(Modifier.fillMaxSize()) {
                ListHead(st, big = false)
                SessionList(st, Modifier.weight(1f), onOpen = { st.sel = it })
            }
        }) {
        Detail(st, active)
    }
}

// ───────────── 펼침 가로 — 사이드바 | 목록 | 대화(또는 두 세션 나란히) ─────────────

@Composable
private fun Landscape(st: AppState, active: Boolean, w: Dp) {
    // 나란히 보기(대화 둘)는 사이드바까지 넣으면 933dp 에 안 들어간다(오른쪽 칸이 60dp 로 짜부라짐 — 실측) →
    // 그동안은 사이드바를 빼고 «목록 | 대화 | 대화». 닫으면 3칸으로 돌아온다
    if (st.sel2 != null) { ListAndDetail(st, active, w, key = "list-cmp", compare = true); return }
    val side = rememberPaneState("side-land", 250f)
    SplitPane(side, w, railW = RAIL, minW = 180f, foldBelow = FOLD, minRest = 320.dp + RAIL + 220.dp,
        pane = { Sidebar(st) }) {
        BoxWithConstraints(Modifier.fillMaxSize()) { ListAndDetail(st, active, maxWidth, key = "list-land", compare = false) }
    }
}

@Composable
private fun ListAndDetail(st: AppState, active: Boolean, w: Dp, key: String, compare: Boolean) {
    val list = rememberPaneState(key, if (compare) 260f else 290f)
    val cmpMin = 280.dp
    SplitPane(list, w, railW = RAIL, minW = 220f, foldBelow = FOLD, minRest = if (compare) cmpMin * 2 + RAIL else 320.dp,
        pane = {
            Column(Modifier.fillMaxSize()) {
                val f = st.folder?.let { st.index?.folders?.get(it) }
                Text((f?.let { "${it.icon ?: "📁"} ${it.name}" } ?: "모든 세션") + " · 길게 누르면 오른쪽에 나란히",
                    color = H.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().background(H.panel2).padding(horizontal = 14.dp, vertical = 9.dp))
                SessionList(st, Modifier.weight(1f), onOpen = { st.sel = it },
                    onOpen2 = { sid -> if (st.sel == null) st.sel = sid else if (sid != st.sel) st.sel2 = sid })
            }
        }) {
        val two = st.sel2
        if (two == null) Detail(st, active)
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            val cmp = rememberPaneState("cmp-land", (maxWidth.value - RAIL.value) / 2)
            SplitPane(cmp, maxWidth, railW = RAIL, minW = cmpMin.value, foldBelow = FOLD, minRest = cmpMin,
                pane = { Detail(st, active) }) {
                ConvPane(st, two, active, cover = false, onBack = null, onClose = { st.sel2 = null })
            }
        }
    }
}

@Composable
private fun Sidebar(st: AppState) {
    Column(Modifier.fillMaxSize().background(H.panel)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Brand(st, big = false)
            MachineLine(st)
            Stats(st)
            SearchField(st, big = false)
            FilterChips(st, folderChip = false, wrap = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(H.border))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text("폴더", color = H.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            FolderTree(st)
        }
    }
}

@Composable
private fun Stats(st: AppState) {
    val ix = st.index ?: return
    val msgs = remember(ix) { ix.items.sumOf { it.messageCount.toLong() } }
    Row(Modifier.fillMaxWidth()) {
        Stat(compact(ix.items.size.toLong()), "세션", Modifier.weight(1f))
        Stat(compact(msgs), "메시지", Modifier.weight(1f))
        Stat("${st.live.size}", "cmux 열림", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(v: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = H.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = H.dim, fontSize = 10.sp)
    }
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
    else -> "$n"
}

/** 오른쪽 대화 칸 — 고른 세션이 없으면 안내. */
@Composable
private fun Detail(st: AppState, active: Boolean) {
    val sid = st.sel
    if (sid == null) {
        Box(Modifier.fillMaxSize().background(H.panel), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hi("chat", H.muted, 40.dp)
                Text("왼쪽 목록에서 세션을 고르세요", color = H.dim, fontSize = 14.sp)
                Text("빌드 ${BuildConfig.BUILD_TIME}", color = H.muted, fontSize = 11.sp)
            }
        }
        return
    }
    ConvPane(st, sid, active, cover = false, onBack = null, onClose = null)
}

