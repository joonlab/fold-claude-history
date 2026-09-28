"""Claude 기록 앱 — 에이전트의 기록 읽기 API (`/api/v1/history/*`). (주석의 DESIGN·D번호는 원래 저장소의 설계 메모 번호다 — 메모는 공개본에 넣지 않았다.)

기록 앱은 **읽기 전용**이다(D2-c). 쓰기는 북마크 하나뿐(D4-b)이고 모두 audit.jsonl 에 남긴다.

데이터는 같은 맥의 두 곳에서 온다.
  - `~/.claude/` 의 파일들 — sessions-index.json(맥마다 따로 만든다) · 이름·태그·설명·폴더·북마크(Syncthing 으로 두 맥 공유)
  - 같은 맥의 history-server(:8080) — 검색·이어진 세션 사슬·인덱스 갱신. 스킬 번들이라 고치지 않고 **부르기만** 한다

⚠️ 실측(2026-09-25): history-server 는 인덱스를 스스로 갱신하지 않는다. **브라우저에 뷰어가 열려 있을 때만**
   60초마다 갱신된다 → 아무도 뷰어를 안 여는 홈맥 인덱스는 3시간 40분 멈춰 있었다. 그래서 목록을 줄 때
   인덱스가 FRESH_SEC 보다 오래됐으면 여기서 증분 갱신(실측 약 2초)을 요청하고 잠깐 기다린다.
"""
import datetime
import glob
import gzip
import hashlib
import json
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from fastapi import APIRouter, HTTPException, Query, Request, Response
from pydantic import BaseModel

import transcript

CLAUDE_DIR = os.path.expanduser(os.environ.get("CMR_CLAUDE_DIR", "~/.claude"))
PROJECTS = os.path.join(CLAUDE_DIR, "projects")
VIEWER = os.environ.get("CMR_HISTORY_URL", "http://127.0.0.1:8080")
FRESH_SEC = float(os.environ.get("CMR_HISTORY_FRESH_SEC", "60"))
WAIT_SEC = 8.0                      # 갱신을 기다리는 최대 시간 — 넘으면 옛 인덱스를 «stale» 로 준다
PREVIEW_MAX = 240

INDEX = os.path.join(CLAUDE_DIR, "sessions-index.json")
SIDE_FILES = {                       # 인덱스에 합칠 부속 파일 — ETag 에도 들어간다
    "names": "session-names.json",
    "tags": "session-tags.json",
    "descriptions": "session-descriptions.json",
    "collections": "session-collections.json",
    "bookmarks": "session-bookmarks.json",
}
_SID = re.compile(r"^[0-9a-fA-F-]{8,64}$")

router = APIRouter(prefix="/api/v1/history")
_meta = lambda: {}                  # server.py 가 set_context 로 채운다(machine · version · now)
_audit = lambda **kw: None
_collect = lambda: {"tabs": []}


def set_context(meta, audit, collect):
    global _meta, _audit, _collect
    _meta, _audit, _collect = meta, audit, collect


# ───────────────────────────── 파일 읽기 ─────────────────────────────

def _load(name, default):
    try:
        with open(os.path.join(CLAUDE_DIR, name), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0.0


def _index_age(generated_at):
    """generatedAt(로컬 시각, 시간대 없음) → 지난 초. 모르면 None."""
    try:
        return time.time() - datetime.datetime.fromisoformat(generated_at).timestamp()
    except (TypeError, ValueError):
        return None


_fresh_lock = threading.Lock()


def _viewer(path, method="GET", timeout=10):
    req = urllib.request.Request(VIEWER + path, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


STALE_WAIT_SEC = float(os.environ.get("CMR_HISTORY_STALE_WAIT_SEC", "600"))


def _refresh_worker(before):
    try:
        _viewer("/api/update-index", method="POST", timeout=5)
        t0 = time.time()
        while time.time() - t0 < 60 and _mtime(INDEX) <= before:
            time.sleep(0.5)
    except (urllib.error.URLError, OSError, ValueError):
        pass
    finally:
        _fresh_lock.release()


def ensure_fresh():
    """인덱스가 FRESH_SEC 보다 오래됐으면 history-server 에 증분 갱신을 요청한다.

    - STALE_WAIT_SEC(10분) 이내로 묵었으면 **기다리지 않는다** — 지금 것을 주고 갱신은 뒤에서 돈다
      (다음 요청 때 ETag 가 바뀌어 새 목록이 간다). 실측: 기다리면 홈맥 목록이 매번 5~6초 막혔다.
    - 그보다 오래됐으면(아무도 안 본 홈맥) 최대 WAIT_SEC 기다린다 — 몇 시간 전 목록을 보여 주지 않게.
    반환: (갱신을 요청했는가, 오류 문구 또는 None). 실패해도 옛 인덱스로 계속 간다 — 대신 응답에 드러낸다.
    """
    idx = _load("sessions-index.json", {})
    age = _index_age(idx.get("generatedAt"))
    if age is not None and age < FRESH_SEC:
        return False, None
    if not _fresh_lock.acquire(blocking=False):
        return False, None            # 다른 요청이 이미 갱신 중
    before = _mtime(INDEX)
    if age is not None and age < STALE_WAIT_SEC:
        threading.Thread(target=_refresh_worker, args=(before,), daemon=True).start()   # 락은 워커가 푼다
        return True, None
    try:
        try:
            _viewer("/api/update-index", method="POST", timeout=5)
        except (urllib.error.URLError, OSError, ValueError) as e:
            return True, f"인덱스 갱신 요청 실패(:8080): {e}"
        t0 = time.time()
        while time.time() - t0 < WAIT_SEC:
            if _mtime(INDEX) > before:
                time.sleep(0.2)       # 쓰는 중인 파일을 읽지 않게
                return True, None
            time.sleep(0.25)
        return True, f"인덱스 갱신이 {WAIT_SEC:.0f}초 안에 끝나지 않아 이전 인덱스를 보냅니다"
    finally:
        _fresh_lock.release()


def _fix_mojibake(s):
    """ghost 의 preview 는 한글이 이중 인코딩돼 저장돼 있다(인덱스 생성 쪽 버그 — 인벤토리 2-4). 되돌릴 수 있을 때만 되돌린다."""
    # 표지: 모든 글자가 1바이트 범위(≤ U+00FF)인데 상위 바이트가 섞여 있다. 한글이 제대로 든 글에는 U+AC00 대가 있다.
    if not s or any(ord(c) > 0xFF for c in s) or not any(ord(c) >= 0x80 for c in s):
        return s
    try:
        fixed = s.encode("latin-1").decode("utf-8", errors="replace")
    except UnicodeEncodeError:
        return s
    fixed = fixed.rstrip("\ufffd")          # 미리보기를 글자 한가운데서 잘라 꼬리가 깨진 것만 버린다
    return s if "\ufffd" in fixed else fixed


def bookmarks():
    """북마크 = {sid: 표시한 시각}. Syncthing 충돌 사본이 있으면 합집합으로 읽는다(원본은 건드리지 않는다)."""
    out = _load(SIDE_FILES["bookmarks"], {})
    if not isinstance(out, dict):
        out = {}
    for p in glob.glob(os.path.join(CLAUDE_DIR, "session-bookmarks.sync-conflict-*.json")):
        try:
            with open(p, encoding="utf-8") as f:
                for k, v in (json.load(f) or {}).items():
                    out.setdefault(k, v)
        except (OSError, ValueError, AttributeError):
            continue
    return out


def build_index():
    """sessions-index 의 session·ghost 항목 + 이름·태그·설명·폴더·북마크를 한 목록으로."""
    idx = _load("sessions-index.json", {})
    names, tags = _load(SIDE_FILES["names"], {}), _load(SIDE_FILES["tags"], {})
    descs, cols = _load(SIDE_FILES["descriptions"], {}), _load(SIDE_FILES["collections"], {})
    marks = bookmarks()
    folders_of = {}
    for cid, c in cols.items():
        for sid in c.get("members") or []:
            folders_of.setdefault(sid, []).append(cid)
    items = []
    for it in idx.get("items") or []:
        typ = it.get("type")
        if typ not in ("session", "ghost"):
            continue
        sid = it.get("sessionId")
        if not sid:
            continue
        hand = it.get("hand") or {}
        items.append({
            "sessionId": sid, "type": typ,
            "project": it.get("project"), "projectFolder": it.get("projectFolder"),
            "fileSize": it.get("fileSize"),
            "first": it.get("firstTimestampISO") or it.get("firstTimestamp"),
            "last": it.get("lastTimestampISO") or it.get("lastTimestamp"),
            "messageCount": it.get("messageCount"),
            "preview": _fix_mojibake((it.get("preview") or "")[:PREVIEW_MAX]),
            "name": names.get(sid) or it.get("customName"),
            "tags": tags.get(sid) or [],
            "description": descs.get(sid),
            "folders": folders_of.get(sid, []),
            "bookmarked": sid in marks,
            "owner": (hand.get("owner") or {}).get("host"),
            "hosts": [h.get("host") for h in hand.get("hosts") or [] if h.get("host")],
        })
    collections = {cid: {k: c.get(k) for k in ("name", "icon", "color", "parent", "description")}
                   | {"count": len(c.get("members") or [])} for cid, c in cols.items()}
    return {"generatedAt": idx.get("generatedAt"), "total": len(items), "items": items,
            "collections": collections}


def index_etag():
    parts = [str(_mtime(INDEX))] + [str(_mtime(os.path.join(CLAUDE_DIR, f))) for f in SIDE_FILES.values()]
    parts += sorted(glob.glob(os.path.join(CLAUDE_DIR, "session-bookmarks.sync-conflict-*.json")))
    return '"' + hashlib.sha1("|".join(parts).encode()).hexdigest()[:20] + '"'


def session_path(sid):
    """sid → 대화 파일. 인덱스의 폴더·파일 이름이 우선, 없으면(방금 생긴 세션) projects/*/ 에서 찾는다.
    결과가 projects 밖이면 거부한다."""
    if not _SID.match(sid or ""):
        raise HTTPException(400, "세션 id 형식이 아닙니다")
    idx = _load("sessions-index.json", {})
    cand = None
    for it in idx.get("items") or []:
        if it.get("sessionId") == sid and it.get("projectFolder") and it.get("fileName"):
            cand = os.path.join(PROJECTS, it["projectFolder"], it["fileName"])
            break
    if not cand or not os.path.exists(cand):
        hits = glob.glob(os.path.join(PROJECTS, "*", sid + ".jsonl"))
        cand = hits[0] if hits else None
    if not cand:
        raise HTTPException(404, f"이 맥에 대화 파일이 없습니다: {sid}")
    real = os.path.realpath(cand)
    if not real.startswith(os.path.realpath(PROJECTS) + os.sep):
        raise HTTPException(400, "대화 파일 경로가 projects 밖입니다")
    return real


def offset_of_uuid(path, uuid):
    """그 uuid 를 가진 줄의 **시작 바이트** — 이어진 세션 사슬의 앞 파일을 거기까지만 읽으려고.
    파일이 GB 단위일 수 있어 파이썬으로 훑지 않고 grep -b 로 찾는다. 못 찾으면 None."""
    if not re.match(r"^[0-9a-fA-F-]{8,64}$", uuid or ""):
        raise HTTPException(400, "uuid 형식이 아닙니다")
    try:
        out = subprocess.run(["grep", "-b", "-m", "1", "-E", f'"uuid": ?"{uuid}"', path],
                             capture_output=True, timeout=60).stdout
    except subprocess.TimeoutExpired:
        return None
    m = re.match(rb"^(\d+):", out)
    return int(m.group(1)) if m else None


# ───────────────────────────── 라우트 ─────────────────────────────

@router.get("/index")
def index(request: Request):
    requested, warn = ensure_fresh()
    etag = index_etag()
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})
    body = {**_meta(), **build_index(), "refreshRequested": requested, "warning": warn}
    body["indexAgeSec"] = _index_age(body.get("generatedAt"))
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    headers = {"ETag": etag, "Cache-Control": "no-cache"}
    if "gzip" in (request.headers.get("accept-encoding") or ""):
        raw = gzip.compress(raw, 6)
        headers["Content-Encoding"] = "gzip"
    return Response(raw, media_type="application/json", headers=headers)


@router.get("/sessions/{sid}/messages")
def messages(sid: str, before: int | None = None, until: str | None = None,
             limit: int = Query(40, ge=5, le=200)):
    """뒤에서부터 limit 개씩(transcript.read_page). until=uuid 면 그 줄 **앞**까지만 — 사슬의 앞 파일용."""
    path = session_path(sid)
    end = before
    if until:
        off = offset_of_uuid(path, until)
        if off is None:
            raise HTTPException(404, f"이 파일에 그 uuid 가 없습니다: {until}")
        end = off if before is None else min(before, off)
    page = transcript.read_page(path, before=end, limit=limit)
    return {**_meta(), "sessionId": sid, "file": os.path.basename(path), **page}


@router.get("/sessions/{sid}/chain")
def chain(sid: str):
    """압축으로 이어진 앞 파일 사슬(오래된 순). 각 segment 는 messages?until= 로 읽는다."""
    if not _SID.match(sid):
        raise HTTPException(400, "세션 id 형식이 아닙니다")
    try:
        d = _viewer(f"/api/history-chain/{sid}", timeout=30)
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise HTTPException(502, f"history-server(:8080) 사슬 조회 실패: {e}") from e
    for s in d.get("segments") or []:
        s["sessionId"] = (s.get("fileName") or "")[:-6] or None
    return {**_meta(), "sessionId": sid, **d}


@router.get("/search")
def search(q: str, scope: str = "content", mode: str = "keyword", limit: int = Query(200, ge=1, le=2000)):
    if scope not in ("content", "deep") or mode not in ("keyword", "semantic", "hybrid"):
        raise HTTPException(400, "scope=content|deep · mode=keyword|semantic|hybrid")
    qs = urllib.parse.urlencode({"q": q, "scope": scope, "mode": mode, "limit": limit})
    try:
        d = _viewer(f"/api/search?{qs}", timeout=30)
    except urllib.error.HTTPError as e:
        raise HTTPException(e.code, f"history-server 검색 실패: {e.read()[:300].decode('utf-8', 'replace')}") from e
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise HTTPException(502, f"history-server(:8080) 검색 실패: {e}") from e
    return {**_meta(), **d}


@router.get("/live")
def live():
    """이 맥의 cmux 에서 지금 살아 있는 claude 탭 — sid → 탭. 이어진 세션이면 옛 id 와 새 id 둘 다로 찾게 한다."""
    out = {}
    for t in _collect().get("tabs") or []:
        row = {k: t.get(k) for k in ("surfaceId", "status", "statusLabel", "windowLabel", "workspaceTitle", "title")}
        for key in (t.get("sessionId"), t.get("liveSessionId")):
            if key:
                out[str(key).lower()] = row
    return {**_meta(), "sessions": out}


@router.get("/bookmarks")
def get_bookmarks():
    return {**_meta(), "bookmarks": bookmarks()}


class BookmarkBody(BaseModel):
    sessionId: str
    on: bool


_bm_lock = threading.Lock()


@router.post("/bookmarks")
def set_bookmark(body: BookmarkBody):
    """기록 앱의 **유일한 쓰기**(D4-b). 불리언 하나라 두 맥 충돌이 나도 피해가 작다 — 읽을 때 합집합."""
    if not _SID.match(body.sessionId):
        raise HTTPException(400, "세션 id 형식이 아닙니다")
    path = os.path.join(CLAUDE_DIR, SIDE_FILES["bookmarks"])
    with _bm_lock:
        cur = _load(SIDE_FILES["bookmarks"], {})
        if not isinstance(cur, dict):
            cur = {}
        if body.on:
            cur[body.sessionId] = cur.get(body.sessionId) or time.time()
        else:
            cur.pop(body.sessionId, None)
        tmp = os.path.join(CLAUDE_DIR, "tmp-session-bookmarks.tmp")   # .stignore 의 /tmp*.tmp — 반쯤 쓴 파일을 Syncthing 이 나르지 않게
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cur, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)
    _audit(kind="bookmark", session=body.sessionId, on=body.on, ok=True)
    return {**_meta(), "ok": True, "sessionId": body.sessionId, "on": body.on}
