package kr.joonlab.cchistory

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.joonlab.core.Http
import kr.joonlab.core.Machine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 앱 전체 상태 — 액티비티 뿌리에서 한 번 만든다. 매니페스트 configChanges 로 자세를 바꿔도 액티비티가
 * 다시 만들어지지 않으므로 선택·검색어·필터·대화 스크롤이 그대로 남는다(DESIGN §4 공통 원칙).
 */
class AppState(private val ctx: Context) {
    private val cache = IndexCache(ctx)
    private var etag: String? = null

    var index by mutableStateOf<HistIndex?>(null)
    var loading by mutableStateOf(false)
    var loadErr by mutableStateOf<String?>(null)
    var live by mutableStateOf<Map<String, LiveRow>>(emptyMap())
    var toast by mutableStateOf<Pair<String, Boolean>?>(null)   // (문구, 오류?)
    /** 관제실 앱이 깔려 있나 — 없으면 «이어가기»·«관제실»·배지 링크를 숨긴다(D7). 앱을 새로 깔면 다음 실행부터 */
    val ctl: Boolean = Ctl.installed(ctx)

    // 선택 — sel2 는 펼침 가로의 «두 세션 나란히» 오른쪽 칸
    var sel by mutableStateOf<String?>(null)
    var sel2 by mutableStateOf<String?>(null)
    var coverDetail by mutableStateOf(false)     // 커버: 대화 화면을 보는 중

    // 필터
    var query by mutableStateOf("")
    var mode by mutableStateOf("hybrid")         // keyword · hybrid · semantic
    var hits by mutableStateOf<List<Hit>?>(null) // 본문 검색 결과(검색 키를 눌렀을 때만)
    var hitsFor by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchErr by mutableStateOf<String?>(null)
    var folder by mutableStateOf<String?>(null)
    var date by mutableStateOf("all")            // all · today · 7d · 30d
    var tags by mutableStateOf<Set<String>>(emptySet())
    var onlyMarked by mutableStateOf(false)
    var onlyLive by mutableStateOf(false)
    var openFolders by mutableStateOf<Set<String>>(emptySet())
    var sheet by mutableStateOf<String?>(null)   // folders · tags (커버·세로의 위에서 덮는 시트)

    val listScroll = LazyListState()

    // 대화 보기 — 도구 줄·시스템/명령 칩을 숨길 수 있다(웹 뷰어의 Tool Calls · 시스템 메시지 토글). 모든 자세에 같이, 저장
    private val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var showTools by mutableStateOf(prefs.getBoolean("showTools", true))
        private set
    var showSys by mutableStateOf(prefs.getBoolean("showSys", true))
        private set
    fun toggleTools() { showTools = !showTools; prefs.edit().putBoolean("showTools", showTools).apply() }
    fun toggleSys() { showSys = !showSys; prefs.edit().putBoolean("showSys", showSys).apply() }

    // 자세(M4) — cover · port · land · book(반접힘, 세로 힌지) · table(반접힘, 가로 힌지)
    var posture: String? = null
        private set
    var tableFolded by mutableStateOf(false)     // 테이블톱: 아래 조작판을 접고 대화를 화면 전체로
    val tableRow = LazyListState()               // 테이블톱 아래 칸의 가로 세션 카드

    /** 자세가 바뀔 때 — 펼친 화면에서 보던 대화가 있으면 커버로 접어도 그 대화를 그대로 보여 준다(목록으로 튕기지 않는다). */
    fun onPosture(p: String) {
        val was = posture
        if (was == p) return
        if (p == "cover" && was != null && sel != null) coverDetail = true
        posture = p
        android.util.Log.i("cchistory", "posture=$p (was $was) sel=${sel?.take(8)} q='$query' folder=$folder")
    }
    private val convs = LinkedHashMap<String, Conv>()

    fun conv(sid: String): Conv = convs.getOrPut(sid) { Conv(sid) }.also {
        // 너무 많이 들고 있지 않는다 — 최근 6개
        while (convs.size > 6) convs.remove(convs.keys.first { k -> k != sel && k != sel2 })
    }

    fun sess(sid: String?) = sid?.let { s -> index?.items?.firstOrNull { it.sid == s } }

    /** 읽을 맥 — 인덱스를 준 맥. 아직 없으면 홈맥. */
    val source: Machine get() = index?.machine ?: ORDER.first()

    suspend fun loadCache() {
        val c = withContext(Dispatchers.IO) { cache.load() } ?: return
        if (index == null) { index = c.first; etag = c.second }
    }

    /** 목록은 열 때·당겨서 새로 고칠 때만 받는다(진행 중 세션이 있으면 매분 바뀐다 — STATUS M1 메모). */
    suspend fun refresh() {
        if (loading) return
        loading = true
        val errs = mutableListOf<String>()
        for (m in ORDER) {
            val tag = if (index?.machine?.key == m.key) etag else null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val f = Http.fetch("${m.base}/api/v1/history/index", tag)
                    if (f.code == 304) { cache.touch(m, tag, now); null }
                    else HistApi.parseIndex(f.body!!, m, now).also { cache.save(f.body!!, m, f.etag, now); etag = f.etag }
                }
            }
            if (r.isSuccess) {
                val got = r.getOrNull()
                index = got ?: index?.copy(fetchedAt = System.currentTimeMillis())
                loadErr = null
                loading = false
                return
            }
            errs.add("${m.label}: ${errText(r.exceptionOrNull()!!)}")
        }
        loadErr = errs.joinToString(" · ")
        loading = false
    }

    /** 살아 있는 세션 — 두 맥 모두에게 묻는다(세션은 어느 맥에서든 돌 수 있다). */
    suspend fun pollLive() {
        val out = HashMap<String, LiveRow>()
        for (m in ORDER) {
            withContext(Dispatchers.IO) { runCatching { HistApi.live(m) } }.onSuccess { out.putAll(it) }
        }
        live = out
    }

    suspend fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) { hits = null; hitsFor = ""; return }
        searching = true
        searchErr = null
        withContext(Dispatchers.IO) { runCatching { HistApi.search(source, t, mode) } }
            .onSuccess { hits = it; hitsFor = t }
            .onFailure { searchErr = "본문 검색 실패: ${errText(it)}"; hits = null }
        searching = false
    }

    suspend fun toggleBookmark(sid: String) {
        val cur = index ?: return
        val s = cur.items.firstOrNull { it.sid == sid } ?: return
        val on = !s.bookmarked
        fun put(v: Boolean) { index = index?.let { ix -> ix.copy(items = ix.items.map { if (it.sid == sid) it.copy(bookmarked = v) else it }) } }
        put(on)
        withContext(Dispatchers.IO) { runCatching { HistApi.bookmark(source, sid, on) } }
            .onSuccess { toast = (if (on) "북마크했습니다" else "북마크를 뺐습니다") to false }
            .onFailure { put(!on); toast = "북마크 실패: ${errText(it)}" to true }
    }

    // ── 필터 ──

    /** 고른 폴더 + 그 아래 폴더 전부(웹 뷰어의 롤업). */
    fun folderSet(id: String): Set<String> {
        val all = index?.folders ?: return setOf(id)
        val out = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (f in all.values) if (f.parent in out && out.add(f.id)) grew = true
        }
        return out
    }

    fun rows(): List<Row> {
        val ix = index ?: return emptyList()
        val q = query.trim().lowercase()
        val fs = folder?.let { folderSet(it) }
        val now = System.currentTimeMillis()
        val since = when (date) {
            "today" -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            "7d" -> now - 7 * 86_400_000L
            "30d" -> now - 30 * 86_400_000L
            else -> 0L
        }
        fun keep(s: Sess) = (fs == null || s.folders.any { it in fs }) &&
            (since == 0L || s.at >= since) &&
            (tags.isEmpty() || s.tags.any { it in tags }) &&
            (!onlyMarked || s.bookmarked) &&
            (!onlyLive || live.containsKey(s.sid))

        val h = hits
        if (h != null && hitsFor.isNotEmpty()) {
            // 본문 검색 — 관련도 순 한 묶음 + 제목·태그로만 맞은 것은 뒤에 따로
            val bySid = ix.items.associateBy { it.sid }
            val body = h.mapNotNull { hit -> bySid[hit.sid]?.takeIf(::keep)?.let { Row.Item(it, hit.snippet) } }
            val seen = body.map { it.s.sid }.toSet()
            val qq = hitsFor.lowercase()
            val local = ix.items.filter { it.sid !in seen && keep(it) && qq in it.hay }.map { Row.Item(it, null) }
            return buildList {
                add(Row.Head("본문 검색 · ${modeLabel(mode)}", body.size))
                addAll(body)
                if (local.isNotEmpty()) { add(Row.Head("제목·태그·설명 일치", local.size)); addAll(local) }
            }
        }
        val list = ix.items.filter { keep(it) && (q.isEmpty() || q in it.hay) }
        // 날짜 묶음(웹 «Today (47)») — 목록은 이미 최근 순
        val out = ArrayList<Row>(list.size + 64)
        var curKey = ""
        var headAt = -1
        var n = 0
        for (s in list) {
            val k = dayKey(s.at)
            if (k != curKey) {
                if (headAt >= 0) out[headAt] = (out[headAt] as Row.Head).copy(count = n)
                curKey = k; headAt = out.size; n = 0
                out.add(Row.Head(dayLabel(s.at), 0))
            }
            out.add(Row.Item(s, null)); n++
        }
        if (headAt >= 0) out[headAt] = (out[headAt] as Row.Head).copy(count = n)
        return out
    }

    val filtered get() = folder != null || date != "all" || tags.isNotEmpty() || onlyMarked || onlyLive || query.isNotBlank()

    fun clearFilters() {
        folder = null; date = "all"; tags = emptySet(); onlyMarked = false; onlyLive = false
        query = ""; hits = null; hitsFor = ""; searchErr = null
    }
}

sealed class Row {
    data class Head(val label: String, val count: Int) : Row()
    data class Item(val s: Sess, val snippet: String?) : Row()
}

fun modeLabel(m: String) = when (m) { "keyword" -> "키워드"; "semantic" -> "의미"; else -> "하이브리드" }
fun dateLabel(d: String) = when (d) { "today" -> "오늘"; "7d" -> "7일"; "30d" -> "30일"; else -> "날짜" }

private val ZONE: ZoneId = ZoneId.systemDefault()
private val DAY_FMT = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val DAY_FMT_Y = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN)

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate()
fun dayKey(ms: Long) = dayOf(ms).toString()
fun dayLabel(ms: Long): String {
    val d = dayOf(ms)
    val today = LocalDate.now(ZONE)
    return when (d) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> d.format(if (d.year == today.year) DAY_FMT else DAY_FMT_Y)
    }
}

/** 목록 시각 — 오늘이면 시:분, 올해면 월-일 시:분, 아니면 연-월-일. */
fun shortTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZONE)
    val today = LocalDate.now(ZONE)
    return when {
        t.toLocalDate() == today -> "오늘 " + t.format(DateTimeFormatter.ofPattern("HH:mm"))
        t.year == today.year -> t.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        else -> t.format(DateTimeFormatter.ofPattern("yy-MM-dd"))
    }
}

fun ago(sec: Double?): String = when {
    sec == null -> "모름"
    sec < 60 -> "방금"
    sec < 3600 -> "${(sec / 60).toInt()}분 전"
    sec < 86400 -> "${(sec / 3600).toInt()}시간 전"
    else -> "${(sec / 86400).toInt()}일 전"
}

fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1fGB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1fMB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024}KB"
}

/**
 * 대화 하나 — 뒤에서부터 페이지로 읽고(열자마자 맨 아래 = 최신), 살아 있으면 꼬리를 갈아 끼운다.
 * 파일 처음에 닿으면 압축으로 이어진 앞 파일(사슬)을 물어 거기서 계속 올라간다.
 */
class Conv(val sid: String) {
    var items by mutableStateOf<List<Msg>>(emptyList())
    var err by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var loadingOlder by mutableStateOf(false)
    var follow by mutableStateOf(true)
    var unseen by mutableIntStateOf(0)
    var find by mutableStateOf<String?>(null)     // null = 대화 찾기 닫힘
    var findAt by mutableIntStateOf(0)
    // 찾기 결과 수·이동 요청 — 찾기 막대가 대화 칸 밖(테이블톱 아래 조작판)에 있어도 같은 규칙으로 움직이게 여기 둔다.
    // findJump 는 «이동해 달라» 횟수. 칸이 composition 을 떠났다 와도 이미 처리한 요청(findDone)은 다시 스크롤하지 않는다
    var findN by mutableIntStateOf(0)
    var findJump by mutableIntStateOf(0)
    var findDone = 0
    fun jump(k: Int) {
        if (findN == 0) return
        findAt = ((k % findN) + findN) % findN
        findJump++
    }
    val list = LazyListState()
    var machine: Machine? = null

    // 커서 — cur = 지금 올라가며 읽는 파일(sid 또는 사슬의 앞 파일), before = 그 파일 안 바이트 위치
    private var cur = sid
    private var until: String? = null
    var before by mutableStateOf<Long?>(null)
    private var segs: List<Seg>? = null
    private var segAt = -1                         // 지금 읽는 사슬 칸(-1 = 이 세션 파일)
    var chainLeft by mutableIntStateOf(0)          // 아직 안 읽은 앞 파일 수(모르면 0)
    val atStart get() = loaded && before == null && chainLeft == 0 && segs != null

    /** 처음 열 때 — 인덱스를 준 맥에서, 안 되면 다른 맥에서. */
    suspend fun open(prefer: Machine) {
        if (loaded) return
        val order = listOf(prefer) + ORDER.filter { it.key != prefer.key }
        var last: Throwable? = null
        for (m in order) {
            val r = withContext(Dispatchers.IO) { runCatching { HistApi.messages(m, sid) } }
            if (r.isSuccess) {
                val p = r.getOrThrow()
                machine = m; items = p.items; before = p.before; err = null; loaded = true
                if (p.before == null) loadChain()
                return
            }
            last = r.exceptionOrNull()
        }
        err = "대화를 못 읽었습니다: ${errText(last!!)}"
    }

    private suspend fun loadChain() {
        if (segs != null) return
        val m = machine ?: return
        withContext(Dispatchers.IO) { runCatching { HistApi.chain(m, sid) } }
            .onSuccess { segs = it; chainLeft = it.size }
            .onFailure { segs = emptyList(); chainLeft = 0 }
    }

    suspend fun older() {
        val m = machine ?: return
        if (loadingOlder) return
        loadingOlder = true
        try {
            if (before == null) {
                loadChain()
                val s = segs ?: return
                if (chainLeft == 0) return
                // 사슬은 오래된 순 — 지금 칸 바로 앞(더 새것부터 거꾸로)
                segAt = if (segAt < 0) s.size - 1 else segAt - 1
                val seg = s[segAt]
                cur = seg.sid; until = seg.untilUuid; chainLeft = segAt
                val p = withContext(Dispatchers.IO) { runCatching { HistApi.messages(m, cur, until = until) } }
                p.onSuccess {
                    items = it.items + Msg("divider", "div-${seg.sid}", null, "이전 파일에서 이어짐 · ${seg.sid.take(8)}") + items
                    before = it.before
                }.onFailure { err = errText(it) }
                return
            }
            val b = before
            withContext(Dispatchers.IO) { runCatching { HistApi.messages(m, cur, before = b, until = until) } }
                .onSuccess { p -> items = p.items + items; before = p.before; if (p.before == null) loadChain() }
                .onFailure { err = errText(it) }
        } finally {
            loadingOlder = false
        }
    }

    /** 최신 페이지를 다시 받아 꼬리만 갈아 끼운다. 이어 붙일 자리를 못 찾으면 통째로(관제실 ChatScreen 과 같은 규칙). 새로 붙은 수를 돌려준다. */
    suspend fun poll(): Int {
        val m = machine ?: return 0
        val p = withContext(Dispatchers.IO) { runCatching { HistApi.messages(m, sid) } }.getOrElse { err = errText(it); return 0 }
        err = null
        val old = items
        val head = p.items.firstOrNull() ?: return 0
        val at = old.indexOfFirst { it.id == head.id && it.kind == head.kind }
        if (at < 0) {
            items = p.items; before = p.before; cur = sid; until = null; segAt = -1; segs = null; chainLeft = 0
            return p.items.size
        }
        val next = old.subList(0, at) + p.items
        val grown = next.size - old.size
        if (grown != 0 || next.lastOrNull() != old.lastOrNull()) items = next
        return grown.coerceAtLeast(0)
    }
}
