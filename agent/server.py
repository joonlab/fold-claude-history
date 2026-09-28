"""Claude 기록 에이전트 — 맥 한 대에 하나씩 뜨는 FastAPI 서버(폰의 «Claude 기록» 앱이 직접 붙는다).

원래는 cmux 모바일 관제실 에이전트의 일부였다. 공개본은 기록 앱이 부르는 것만 남겼다.
  - /api/v1/health
  - /api/v1/history/*                       기록 목록·대화·검색·사슬·북마크·살아 있는 탭(history.py)
  - /api/v1/history/sessions/{sid}/resume-check · resume   폰에서 «이어가기»(resume.py + cmux 연동)

노출: 127.0.0.1 에만 바인딩하고 `tailscale serve --https=8797 http://127.0.0.1:7797` 같은 방법으로
      내 기기들 안에서만 닿게 한다. 인증이 없으므로 **공개 인터넷에 열면 안 된다.**

설정: agent/config.json(예시 config.example.json) → 환경변수(CMR_MACHINE · CMR_SEND · CMR_PEER_URL · CMR_HAND_LIB)가 덮어쓴다.
"""
import json
import os
import socket
import subprocess
import threading
import time

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import cmux_bridge
from cmux_bridge import DATA_DIR, REPO_DIR

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))


def _config():
    try:
        with open(os.path.join(AGENT_DIR, "config.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


CFG = _config()
MACHINE = os.environ.get("CMR_MACHINE") or CFG.get("machine") or socket.gethostname().split(".")[0]
# 이어가기는 터미널에 명령을 치는 일이다 — 기본은 off. off 가 아니어야(on) /resume 이 동작한다.
SEND_MODE = os.environ.get("CMR_SEND") or CFG.get("send") or "off"
if CFG.get("hand_lib") and not os.environ.get("CMR_HAND_LIB"):
    os.environ["CMR_HAND_LIB"] = CFG["hand_lib"]

import history  # noqa: E402
import resume as resumemod  # noqa: E402

resumemod.PEERS = {k: (v.get("name"), v.get("url")) for k, v in (CFG.get("peers") or {}).items() if v.get("url")}

API = "/api/v1"


def _git_sha(path):
    try:
        return subprocess.run(["git", "-C", path, "rev-parse", "--short", "HEAD"],
                              capture_output=True, timeout=5).stdout.decode().strip() or None
    except Exception:                                          # noqa: BLE001
        return None


# 두 맥의 에이전트 버전이 어긋나면 같은 화면이 다르게 보인다 → 응답마다 실어 앱이 드러내게 한다.
VERSION = {"agent": _git_sha(REPO_DIR), "vendor": _git_sha(cmux_bridge.VENDOR) if cmux_bridge.ENABLED else None}

app = FastAPI(title="Claude history agent")
AUDIT = os.path.join(DATA_DIR, "audit.jsonl")
_SEND_LOCK = threading.Lock()


def _meta():
    return {"machine": MACHINE, "version": VERSION, "now": time.time()}


def _audit(**kw):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(AUDIT, "a", encoding="utf-8") as f:
        f.write(json.dumps({"at": time.time(), **kw}, ensure_ascii=False) + "\n")


def _collect():
    try:
        return cmux_bridge.collect()
    except Exception as e:                                     # noqa: BLE001
        # 수집 자체가 죽었으면 «0개»가 아니라 실패로 알린다 — 빈 목록은 «탭 없음»으로 읽힌다.
        raise HTTPException(503, f"탭 수집 실패: {e}") from e


@app.get(f"{API}/health")
def health():
    return {**_meta(), "ok": True, "cmux": cmux_bridge.ENABLED, "cmuxError": cmux_bridge.ERROR,
            "socket": cmux_bridge.ping(), "send": SEND_MODE}


# ─────────────────────────── 기록 (/api/v1/history/*) ───────────────────────────
history.set_context(_meta, _audit, _collect)
app.include_router(history.router)


# ─────────────────────── 이어가기 ───────────────────────
#
# 폰이 맥을 고르면(기본값 없음) 먼저 두 맥 모두에 resume-check 를 묻고, 고른 맥에 resume 을 보낸다.
# resume 은 같은 검사를 **여기서 다시** 한다 — 폰이 본 뒤로 상황이 바뀌었을 수 있다(두 번째 겹).

def _resume_state(sid):
    sid = (sid or "").strip().lower()
    path = history.session_path(sid)                 # 형식 검사 + 이 맥에 대화 파일이 있나(없으면 404)
    live = None
    for t in _collect().get("tabs") or []:
        if sid in (str(t.get("sessionId") or "").lower(), str(t.get("liveSessionId") or "").lower()):
            live = {"surfaceId": t.get("surfaceId"), "status": t.get("status"), "statusLabel": t.get("statusLabel"),
                    "workspaceTitle": t.get("workspaceTitle")}
            break
    owner = resumemod.owner_of(sid)
    cwd = resumemod.session_cwd(path)
    cwd_ok = bool(cwd and os.path.isdir(cwd))
    peer_name, peer = (None, None) if live else resumemod.peer_live(MACHINE, sid)
    action, reason = resumemod.verdict(live, owner, cwd, cwd_ok, peer, peer_name)
    names = history._load(history.SIDE_FILES["names"], {})
    return {"sessionId": sid, "name": names.get(sid), "live": live, "owner": owner, "cwd": cwd, "cwdExists": cwd_ok,
            "peer": {"name": peer_name, "live": peer}, "action": action, "reason": reason}


@app.get(API + "/history/sessions/{sid}/resume-check")
def resume_check(sid: str):
    return {**_meta(), **_resume_state(sid)}


class ResumeBody(BaseModel):
    force: bool = False               # owner 를 확인할 수 없을 때(action=confirm) 사람이 «그래도» 를 눌렀다
    skipPermissions: bool = False     # claude --dangerously-skip-permissions


@app.post(API + "/history/sessions/{sid}/resume")
def resume(sid: str, body: ResumeBody):
    if SEND_MODE == "off":
        raise HTTPException(403, "이어가기가 꺼져 있습니다(CMR_SEND=on 또는 config.json 의 \"send\": \"on\")")
    if not cmux_bridge.ENABLED:
        raise HTTPException(501, cmux_bridge.ERROR)
    st = _resume_state(sid)
    sid = st["sessionId"]
    if st["action"] == "open":
        _audit(kind="resume", ok=True, session=sid, already=st["live"]["surfaceId"])
        return {**_meta(), "ok": True, "already": True, **st, "surfaces": [st["live"]["surfaceId"]]}
    if st["action"] == "block" or (st["action"] == "confirm" and not body.force):
        _audit(kind="resume", ok=False, session=sid, action=st["action"], reason=st["reason"])
        raise HTTPException(409, {"reason": st["reason"], "action": st["action"]})
    # `--resume` 긴 형태로 — 대시보드는 명령줄의 `--session-id`/`--resume` 만 읽어 탭↔세션을 잇는다.
    # `-r` 로 띄우면 탭의 sessionId 가 비어 «살아 있음»·다른 맥 검사가 이 탭을 못 본다(실측)
    cmd = f"claude {'--dangerously-skip-permissions ' if body.skipPermissions else ''}--resume {sid}"
    name = "↩ " + ((st["name"] or "").strip() or sid[:8])
    cc = cmux_bridge.cmux_client
    with _SEND_LOCK:
        try:
            out, ref = cc.new_workspace(name=name[:60], cwd=st["cwd"], command=cmd, focus=False)
        except cc.CmuxError as e:
            _audit(kind="resume", ok=False, session=sid, error=str(e)[:300])
            raise HTTPException(502, str(e)) from e
        if not ref:
            raise HTTPException(502, f"새 워크스페이스 ref 를 못 받았습니다: {out[:200]}")
        wt = cc.workspace_tree(ref)
    ws = next((x for w in wt.get("windows", []) for x in w.get("workspaces", []) if x.get("ref") == ref), None)
    wid = str((ws or {}).get("id") or "").upper()
    surfaces = [str(s.get("id")).upper() for p in (ws or {}).get("panes", []) for s in p.get("surfaces", [])]
    _audit(kind="resume", ok=True, session=sid, cwd=st["cwd"], command=cmd, workspace=wid, force=body.force)
    return {**_meta(), "ok": True, "already": False, **st, "workspaceId": wid, "surfaces": surfaces, "command": cmd}
