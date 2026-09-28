"""cmux 연동(선택) — «지금 살아 있는 claude 탭» 읽기와 «새 워크스페이스에서 이어가기» 두 가지만.

cmux 쪽 세션↔탭 조인·상태 판정은 공개 저장소 cmux-state-dashboard 의 코드를 그대로 불러 쓴다
(https://github.com/joonlab/cmux-state-dashboard). 같은 판정을 여기서 다시 짜면 같은 사고를 다시 밟는다.

  git clone https://github.com/joonlab/cmux-state-dashboard vendor/cmux-dash
  (또는 CMR_CMUX_DASH_DIR 로 다른 경로를 준다)

없으면 에이전트는 **기록 읽기 전용**으로 뜬다: /live 는 빈 목록, 이어가기는 501.
"""
import os
import sys

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(AGENT_DIR)
DATA_DIR = os.environ.get("CMR_DATA", os.path.join(AGENT_DIR, "data"))
VENDOR = os.path.expanduser(os.environ.get("CMR_CMUX_DASH_DIR", os.path.join(REPO_DIR, "vendor", "cmux-dash")))

nav = None
cmux_client = None
ERROR = None

if os.path.isfile(os.path.join(VENDOR, "nav.py")):
    os.makedirs(DATA_DIR, exist_ok=True)
    # 대시보드 config 는 import 시점에 CMUX_DASH_DATA 를 읽는다 — 그 저장소 안에 쓰지 않게 먼저 돌린다
    os.environ.setdefault("CMUX_DASH_DATA", DATA_DIR)
    if VENDOR not in sys.path:
        sys.path.insert(0, VENDOR)
    try:
        import cmux_client  # noqa: E402,F811
        import nav  # noqa: E402,F811
    except Exception as e:                                     # noqa: BLE001
        nav, cmux_client, ERROR = None, None, f"cmux-state-dashboard 를 불러오지 못했습니다: {e}"
else:
    ERROR = f"cmux 연동 꺼짐 — {VENDOR} 에 cmux-state-dashboard 가 없습니다"

ENABLED = nav is not None


def collect():
    """살아 있는 claude 탭 목록 {"tabs": [...]} — 연동이 꺼져 있으면 빈 목록."""
    if not ENABLED:
        return {"tabs": []}
    return nav.collect()


def ping():
    if not ENABLED:
        return None
    try:
        return cmux_client.ping()
    except Exception:                                          # noqa: BLE001
        return False
