"""기록 앱 → 이어가기(M5) — 세션을 이 맥의 cmux 새 워크스페이스에서 `claude --resume <sid>` 로 되살리기 전에 무엇을 확인하나.

두 맥 운용 규칙 1: **세션은 한 번에 한 머신에서만 진행한다.** 그래서 이어가기는 세 가지를 본다.
  ① 이 맥 cmux 에 이미 살아 있나 — 그러면 새로 띄우지 않고 그 탭을 연다
  ② hand 사이드카(`<sid>.hand.json`)의 owner — 다른 맥이 잡고 있고 그 pid 가 살아 있으면 막는다.
     이 맥이 잡고 있는데 cmux 탭엔 없으면(iTerm·다른 창) 역시 막는다 — 한 세션을 두 프로세스가 쓰게 된다
  ③ 세션의 작업 폴더가 이 맥에 있나 — `claude -r` 은 폴더와 무관하게 되살아나지만(2.1.263 실측)
     프로젝트 훅·메모리가 폴더를 따르므로 원래 폴더에서 연다

  ④ 상대 맥 cmux 에 살아 있나 — 상대 에이전트의 /history/live 를 직접 묻는다.
     ★ hand 사이드카는 Syncthing 으로 건너오므로 **늦다**(M5 실측: 노트북에서 막 이어간 세션을 홈맥은 몇 초간
     옛 pid(이미 죽음)로 봤다 → 사이드카만 믿으면 통과시킨다). 그래서 상대의 cmux 를 직접 본다

owner 판정(②)은 제 개인 도구인 hand(두 맥 세션 인계 스크립트)의 라이브러리를 불러 쓴다(pid 생존 = 로컬 kill 0 · 원격 ssh ps).
공개본에서는 **선택 사항**이다 — CMR_HAND_LIB 에 같은 인터페이스(me · by_label · pid_alive_local · pid_alive_remote)를
가진 파이썬 파일 경로를 주면 쓰고, 없으면 owner 를 «모름»으로 두고 ①·③·④ 만으로 판정한다.
"""
import glob
import importlib.util
import json
import os
import unicodedata
import urllib.request

CLAUDE_DIR = os.path.expanduser(os.environ.get("CMR_CLAUDE_DIR", "~/.claude"))
HAND_LIB = os.path.expanduser(os.environ.get("CMR_HAND_LIB", ""))   # 선택 — 비우면 owner 판정을 건너뛴다
CWD_SCAN_LINES = 400                 # cwd 는 첫 몇 줄 안에 나온다 — 거대한 줄을 끝까지 읽지 않게 줄 수·줄 길이 상한
CWD_LINE_MAX = 1 << 20

# 상대 맥 에이전트 — {이 맥의 이름: (상대 표시 이름, 상대 에이전트 URL)}. 주소는 코드에 박지 않는다.
# server.py 가 config.json 의 "peers" 로 채운다(예시 config.example.json). 환경변수 CMR_PEER_URL 이 있으면 그걸 쓴다.
PEERS = {}
PEER_TIMEOUT = 5

_hand = None


def peer_live(machine, sid):
    """상대 맥 cmux 에 이 세션 탭이 있나 → (상대 이름, True·False·None=물어볼 수 없음)."""
    name, url = PEERS.get(machine, (None, None))
    url = os.environ.get("CMR_PEER_URL") or url
    if not url:
        return None, None
    try:
        with urllib.request.urlopen(url + "/api/v1/history/live", timeout=PEER_TIMEOUT) as r:
            d = json.load(r)
        return name, sid.lower() in (d.get("sessions") or {})
    except Exception:                                       # noqa: BLE001 — 꺼져 있으면 모른다
        return name, None


def hand():
    """hand-lib 모듈(없으면 None — 그땐 owner 를 «모름»으로 둔다)."""
    global _hand
    if _hand is None and HAND_LIB and os.path.exists(HAND_LIB):
        spec = importlib.util.spec_from_file_location("hand_lib", HAND_LIB)
        if spec is None or spec.loader is None:
            return None
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        _hand = mod
    return _hand


def session_cwd(path):
    """대화 파일의 작업 폴더 — 처음 나오는 `cwd`. 폴더 이름은 NFC 로(Claude Code 가 그렇게 쓴다)."""
    try:
        with open(path, "rb") as f:
            for _ in range(CWD_SCAN_LINES):
                line = f.readline(CWD_LINE_MAX)
                if not line:
                    break
                if b'"cwd"' not in line:
                    continue
                try:
                    cwd = json.loads(line).get("cwd")
                except ValueError:
                    continue
                if cwd:
                    return unicodedata.normalize("NFC", cwd)
    except OSError:
        pass
    return None


def owner_of(sid):
    """hand 사이드카의 owner → {host, pid, mine, alive(True·False·None=확인 불가)} 또는 None(아무도 안 잡음)."""
    h = hand()
    side = glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", sid + ".hand.json"))
    if not side:
        return None
    try:
        with open(side[0], encoding="utf-8") as f:
            ow = (json.load(f) or {}).get("owner")
    except (OSError, ValueError):
        return None
    if not ow or not ow.get("host"):
        return None
    out = {"host": ow["host"], "pid": ow.get("pid"), "since": ow.get("since"), "mine": None, "alive": None}
    if h is None or not ow.get("pid"):
        return out
    me = h.me()["label"]
    out["mine"] = ow["host"] == me
    if out["mine"]:
        out["alive"] = h.pid_alive_local(ow["pid"])
    else:
        host = h.by_label(ow["host"])
        out["alive"] = h.pid_alive_remote(host, ow["pid"]) if host else None
    return out


def verdict(live_here, owner, cwd, cwd_ok, peer=None, peer_name=None):
    """무엇을 할지 — (action, 이유). action: open(이미 여기 cmux 에) · block · confirm(확인 불가 — 명시해야 진행) · resume.
    peer = 상대 맥 cmux 에 살아 있나(True·False·None=모름)."""
    if live_here:
        return "open", "이 맥 cmux 에서 이미 돌고 있습니다 — 그 탭을 엽니다"
    if peer is True:
        return "block", f"{peer_name or '다른 맥'} cmux 에서 돌고 있습니다 — 한 세션은 한 맥에서만"
    if owner and owner.get("alive") is True:
        if owner.get("mine"):
            return "block", f"이 맥에서 cmux 밖(pid {owner.get('pid')})에서 돌고 있습니다 — 거기서 이어가세요"
        return "block", f"{owner['host']} 에서 진행 중입니다(pid {owner.get('pid')}) — 한 세션은 한 맥에서만"
    if not cwd:
        return "block", "대화 파일에서 작업 폴더를 못 찾았습니다"
    if not cwd_ok:
        return "block", f"이 맥에 작업 폴더가 없습니다: {cwd}"
    if owner and owner.get("alive") is None and owner.get("mine") is False:
        return "confirm", f"{owner['host']} 이 잡고 있다고 적혀 있는데 지금 확인할 수 없습니다(ssh 불가)"
    return "resume", "이어갈 수 있습니다"
