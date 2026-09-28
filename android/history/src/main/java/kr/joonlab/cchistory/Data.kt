package kr.joonlab.cchistory

import android.content.Context
import kr.joonlab.core.ApiError
import kr.joonlab.core.Http
import kr.joonlab.core.MACHINES
import kr.joonlab.core.Machine
import kr.joonlab.core.dbl
import kr.joonlab.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/** 기록 목록 한 줄(에이전트 /api/v1/history/index 의 items[]). 시각은 epoch ms. */
data class Sess(
    val sid: String,
    val type: String,               // session · ghost
    val project: String,
    val fileSize: Long,
    val first: Long?,
    val last: Long?,
    val messageCount: Int,
    val preview: String,
    val name: String?,
    val tags: List<String>,
    val description: String?,
    val folders: List<String>,
    val bookmarked: Boolean,
    val owner: String?,             // hand — 지금 이 세션을 잡고 있는 맥(laptop · home)
    val hosts: List<String>,
) {
    val title get() = name ?: preview.ifBlank { "(내용 없음)" }
    /** 앱 안 즉시 필터용 — 제목·미리보기·태그·설명(웹의 «전체» 범위에서 본문을 뺀 것). */
    val hay: String by lazy { listOfNotNull(name, preview, description, tags.joinToString(" "), project).joinToString("\n").lowercase() }
    val at get() = last ?: first ?: 0L
}

data class Folder(val id: String, val name: String, val icon: String?, val color: String?, val parent: String?,
                  val description: String?, val count: Int)

data class HistIndex(
    val machine: Machine,
    val generatedAt: String?,
    val indexAgeSec: Double?,
    val fetchedAt: Long,            // 폰이 받은(또는 304 로 확인한) 시각
    val items: List<Sess>,
    val folders: Map<String, Folder>,
) {
    /** 지금 기준 인덱스 나이(초) — 받은 뒤 흐른 시간을 더한다. 모르면 null. */
    fun ageSec(now: Long = System.currentTimeMillis()) = indexAgeSec?.let { it + (now - fetchedAt) / 1000.0 }
}

/** 대화 한 줄(transcript.parse_line 의 항목). divider 는 앱이 끼우는 «이전 파일에서 이어짐» 줄이다. */
data class Msg(
    val kind: String,               // user · assistant · tool · question · system · command · divider
    val id: String?,
    val ts: Double?,
    val text: String?,
    val name: String? = null,
    val summary: String? = null,
    val status: String? = null,     // tool/question: done · error · pending · null(모름)
    val questions: JSONArray? = null,
    val answer: String? = null,
)

data class Page(val items: List<Msg>, val before: Long?)
data class Seg(val sid: String, val untilUuid: String?, val count: Int)
data class Hit(val sid: String, val snippet: String?, val score: Double?)
data class LiveRow(val machine: Machine, val surfaceId: String, val status: String, val statusLabel: String, val title: String)

/** D2 — 홈맥 우선, 안 되면 노트북. */
val ORDER: List<Machine> = MACHINES.sortedBy { if (it.key == "home") 0 else 1 }
fun machineOf(key: String?) = MACHINES.firstOrNull { it.key == key }

private val TAGS_RE = Regex("<[^>]{1,200}>")
private val WS_RE = Regex("\\s+")

/** 미리보기의 주입 태그(<pasted_content …> 등)를 떼고 한 줄로. */
fun cleanPreview(s: String) = WS_RE.replace(TAGS_RE.replace(s, " "), " ").trim()

private fun parseTime(v: Any?): Long? = when (v) {
    null, JSONObject.NULL -> null
    is Number -> v.toLong().let { if (it < 100_000_000_000L) it * 1000 else it }   // ghost 는 epoch ms
    is String -> runCatching { Instant.parse(v).toEpochMilli() }.recoverCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        .getOrNull()
    else -> null
}

private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }

object HistApi {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun parseIndex(body: String, m: Machine, fetchedAt: Long): HistIndex {
        val d = JSONObject(body)
        val arr = d.getJSONArray("items")
        val items = ArrayList<Sess>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(Sess(
                sid = o.getString("sessionId"), type = o.optString("type", "session"),
                project = o.optString("project"), fileSize = o.optLong("fileSize"),
                first = parseTime(o.opt("first")), last = parseTime(o.opt("last")),
                messageCount = o.optInt("messageCount"),
                preview = cleanPreview(o.optString("preview")),
                name = o.str("name")?.takeIf { it.isNotBlank() },
                tags = o.optJSONArray("tags").strings(),
                description = o.str("description")?.takeIf { it.isNotBlank() },
                folders = o.optJSONArray("folders").strings(),
                bookmarked = o.optBoolean("bookmarked"),
                owner = o.str("owner"),
                hosts = o.optJSONArray("hosts").strings(),
            ))
        }
        items.sortByDescending { it.at }
        val cols = d.optJSONObject("collections") ?: JSONObject()
        val folders = cols.keys().asSequence().associateWith { id ->
            val c = cols.getJSONObject(id)
            Folder(id, c.optString("name", id), c.str("icon"), c.str("color"), c.str("parent"), c.str("description"), c.optInt("count"))
        }
        return HistIndex(m, d.str("generatedAt"), d.dbl("indexAgeSec"), fetchedAt, items, folders)
    }

    fun messages(m: Machine, sid: String, before: Long? = null, until: String? = null, limit: Int = 40): Page {
        val q = buildString {
            append("?limit=$limit")
            if (before != null) append("&before=$before")
            if (until != null) append("&until=${enc(until)}")
        }
        val d = Http.call("${m.base}/api/v1/history/sessions/$sid/messages$q", readMs = 20000)
        val arr = d.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Msg(kind = o.optString("kind"), id = o.str("id") ?: o.str("toolUseId"), ts = o.dbl("ts"), text = o.str("text"),
                name = o.str("name"), summary = o.str("summary"), status = o.str("status"),
                questions = o.optJSONArray("questions"), answer = o.str("answer"))
        }
        return Page(items, if (d.isNull("before")) null else d.getLong("before"))
    }

    fun chain(m: Machine, sid: String): List<Seg> {
        val d = Http.call("${m.base}/api/v1/history/sessions/$sid/chain", readMs = 35000)
        val arr = d.optJSONArray("segments") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            o.str("sessionId")?.let { Seg(it, o.str("untilUuid"), o.optInt("messageCount")) }
        }
    }

    fun search(m: Machine, q: String, mode: String, limit: Int = 200): List<Hit> {
        val d = Http.call("${m.base}/api/v1/history/search?q=${enc(q)}&scope=content&mode=$mode&limit=$limit", readMs = 35000)
        val arr = d.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Hit(o.getString("key"), o.str("snippet"), o.dbl("score"))
        }
    }

    fun live(m: Machine): Map<String, LiveRow> {
        val d = Http.call("${m.base}/api/v1/history/live", readMs = 8000)
        val s = d.optJSONObject("sessions") ?: return emptyMap()
        return s.keys().asSequence().associateWith { k ->
            val o = s.getJSONObject(k)
            LiveRow(m, o.optString("surfaceId"), o.optString("status"), o.optString("statusLabel"), o.optString("title"))
        }
    }

    /** 기록 앱의 유일한 쓰기(D4-b). */
    fun bookmark(m: Machine, sid: String, on: Boolean): JSONObject =
        Http.call("${m.base}/api/v1/history/bookmarks", JSONObject().put("sessionId", sid).put("on", on))
}

/**
 * 인덱스 캐시 — 열 때 바로 목록을 보이고(오프라인에서도), 받을 때는 ETag 로 304 를 노린다.
 * ETag 는 맥마다 다르므로 캐시가 온 맥을 같이 적는다.
 */
class IndexCache(ctx: Context) {
    private val body = File(ctx.filesDir, "history-index.json")
    private val meta = File(ctx.filesDir, "history-index.meta.json")

    fun load(): Pair<HistIndex, String?>? = runCatching {
        if (!body.exists() || !meta.exists()) return null
        val mt = JSONObject(meta.readText())
        val m = machineOf(mt.optString("machine")) ?: return null
        HistApi.parseIndex(body.readText(), m, mt.optLong("fetchedAt")) to mt.str("etag")
    }.getOrNull()

    fun save(raw: String, m: Machine, etag: String?, fetchedAt: Long) {
        body.writeText(raw)
        touch(m, etag, fetchedAt)
    }

    fun touch(m: Machine, etag: String?, fetchedAt: Long) {
        meta.writeText(JSONObject().put("machine", m.key).put("etag", etag).put("fetchedAt", fetchedAt).toString())
    }
}

fun errText(e: Throwable): String = when (e) {
    is ApiError -> e.message ?: "오류"
    is java.net.SocketTimeoutException -> "시간 초과"
    is java.net.UnknownHostException -> "주소를 못 찾음(Tailscale 꺼짐?)"
    is java.net.ConnectException -> "연결 거부"
    else -> e.message ?: e.javaClass.simpleName
}

/**
 * 관제실 앱으로 건너가기(DESIGN D7). 기록 앱은 쓰기를 하지 않는다 — 이어가기도 링크만 열고,
 * 맥 고르기·다른 맥 실행 검사·워크스페이스 만들기는 관제실 앱이 맡는다.
 * 관제실 앱이 없으면 버튼을 숨긴다(누르면 죽는 버튼을 두지 않는다). 매니페스트 <queries> 필요.
 */
object Ctl {
    fun installed(ctx: Context) = android.content.Intent(android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("cmuxremote://tab/laptop/00000000-0000-0000-0000-000000000000")).resolveActivity(ctx.packageManager) != null

    private fun open(ctx: Context, uri: String) = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** 살아 있는 탭 → 관제실의 그 탭 대화. 맥과 surface 를 늘 명시한다. */
    fun openTab(ctx: Context, live: LiveRow) = open(ctx, "cmuxremote://tab/${live.machine.key}/${live.surfaceId}")

    fun resume(ctx: Context, sid: String, title: String?) =
        open(ctx, "cmuxremote://resume/$sid" + (title?.let { "?title=" + URLEncoder.encode(it.take(120), "UTF-8") } ?: ""))
}

/** cchistory://session/{sid} → sid (관제실 대화 머리의 «전체 기록»). */
fun linkSid(u: android.net.Uri?): String? =
    u?.takeIf { it.scheme == "cchistory" && it.host == "session" }?.pathSegments?.firstOrNull()
        ?.takeIf { Regex("^[0-9a-fA-F-]{8,64}$").matches(it) }?.lowercase()
