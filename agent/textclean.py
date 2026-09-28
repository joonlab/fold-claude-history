"""사람 발화에서 덧붙은 소음을 걷어내는 정규식 — cmux-state-dashboard(nav.py, MIT, 같은 저자)에서 그대로 옮겼다.

transcript.py 가 쓰는 것만 떼어 와서, 기록 읽기는 cmux 대시보드 없이도 돈다.
"""
import re

# 애초에 사람 발화가 아닌 것(Monitor 알림·작업 완료 통지) → 통째로 버린다
_SKIP = re.compile(r"Monitor event:|task-notification|local-command-stdout", re.I)
# 사람 발화에 **덧붙은** 부분(system-reminder·훅 출력)만 잘라내고 사람이 쓴 본문은 살린다
_SYSREM = re.compile(r"<system-reminder>[\s\S]*?</system-reminder>", re.I)
_HOOK_TAIL = re.compile(r"\n*\w[\w:]* hook success[\s\S]*$", re.I)
# 제 UserPromptSubmit 훅(스킬 라우터)이 프롬프트 뒤에 붙이는 안내 줄 — 다른 훅을 쓰면 여기에 더한다
_LINE_NOISE = re.compile(r"^\s*(\[스킬 라우터\]|→ 위 후보의|\(스킬 \d+개 스캔).*$", re.M)
