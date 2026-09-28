"""기록 API(history.py) — 임시 ~/.claude 로만 돈다. 진짜 ~/.claude 는 읽지도 쓰지도 않는다."""
import json
import os
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="cmr-history-test-")
os.environ["CMR_CLAUDE_DIR"] = TMP
os.environ["CMR_HISTORY_URL"] = "http://127.0.0.1:9"      # 닫힌 포트 — 이 테스트는 :8080 을 부르지 않는다
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import history  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A = "aaaaaaaa-1111-2222-3333-444444444444"
B = "bbbbbbbb-1111-2222-3333-444444444444"
G = "cccccccc-1111-2222-3333-444444444444"


def w(name, obj):
    with open(os.path.join(TMP, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)


def setup():
    os.makedirs(os.path.join(TMP, "projects", "-Users-demo"), exist_ok=True)
    with open(os.path.join(TMP, "projects", "-Users-demo", A + ".jsonl"), "w", encoding="utf-8") as f:
        f.write(json.dumps({"type": "user", "uuid": "0001aaaa-0000", "message": {"role": "user", "content": "첫 질문"},
                            "timestamp": "2026-09-25T01:00:00Z"}, ensure_ascii=False) + "\n")
        f.write(json.dumps({"type": "user", "uuid": "0002bbbb-0000", "message": {"role": "user", "content": "둘째 질문"},
                            "timestamp": "2026-09-25T01:01:00Z"}, ensure_ascii=False) + "\n")
    w("sessions-index.json", {"generatedAt": "2026-09-25T10:00:00", "items": [
        {"type": "session", "sessionId": A, "projectFolder": "-Users-demo", "fileName": A + ".jsonl",
         "messageCount": 2, "preview": "x" * 500, "customName": "인덱스 이름",
         "hand": {"owner": {"host": "laptop"}, "hosts": [{"host": "laptop"}, {"host": "home"}]}},
        {"type": "session", "sessionId": B, "projectFolder": "-Users-demo", "fileName": B + ".jsonl"},
        {"type": "ghost", "sessionId": G, "preview": "/Users/demo/폴드".encode("utf-8").decode("latin-1")[:-1]},
        {"type": "history", "historyId": "h1"},
    ]})
    w("session-names.json", {A: "큐레이션 이름"})
    w("session-tags.json", {A: ["모바일"]})
    w("session-descriptions.json", {A: "설명"})
    w("session-collections.json", {"hv": {"name": "history viewer", "members": [A, B]},
                                   "sub": {"name": "하위", "parent": "hv", "members": [A]}})
    w("session-bookmarks.json", {A: 1.0})
    w("session-bookmarks.sync-conflict-20260925-1.json", {B: 2.0})


def test_build_index_merges_side_files():
    setup()
    d = history.build_index()
    assert d["total"] == 3                                        # history 항목은 뺀다
    a = next(i for i in d["items"] if i["sessionId"] == A)
    assert a["name"] == "큐레이션 이름"                             # 이름 파일이 인덱스의 customName 보다 우선
    assert a["tags"] == ["모바일"] and a["description"] == "설명"
    assert sorted(a["folders"]) == ["hv", "sub"] and a["bookmarked"] is True
    assert len(a["preview"]) == history.PREVIEW_MAX
    assert a["owner"] == "laptop" and a["hosts"] == ["laptop", "home"]
    b = next(i for i in d["items"] if i["sessionId"] == B)
    assert b["bookmarked"] is True                                # 충돌 사본의 북마크도 합집합으로
    assert d["collections"]["hv"]["count"] == 2 and d["collections"]["sub"]["parent"] == "hv"


def test_ghost_mojibake_repaired():
    setup()
    g = next(i for i in history.build_index()["items"] if i["sessionId"] == G)
    assert g["preview"] == "/Users/demo/폴"                       # 잘린 마지막 글자만 버린다
    assert history._fix_mojibake("정상 한글") == "정상 한글" and history._fix_mojibake("abc") == "abc"


def test_session_path_guards():
    setup()
    assert history.session_path(A).endswith(A + ".jsonl")
    for bad, code in (("../../etc/passwd", 400), (B, 404)):       # 형식 오류 · 파일 없음
        try:
            history.session_path(bad)
            assert False, bad
        except HTTPException as e:
            assert e.status_code == code, (bad, e.status_code)


def test_offset_of_uuid_cuts_before_that_line():
    setup()
    p = history.session_path(A)
    off = history.offset_of_uuid(p, "0002bbbb-0000")
    with open(p, "rb") as f:
        first = f.readline()
    assert off == len(first)
    assert history.offset_of_uuid(p, "9999ffff-0000") is None
    page = history.messages(A, until="0002bbbb-0000", limit=40)   # 라우트를 직접 부르면 Query 기본값이 안 풀린다
    assert [i["text"] for i in page["items"] if i["kind"] == "user"] == ["첫 질문"]


def test_bookmark_write_is_atomic_and_audited():
    setup()
    seen = []
    history.set_context(lambda: {}, lambda **kw: seen.append(kw), lambda: {"tabs": []})
    history.set_bookmark(history.BookmarkBody(sessionId=B, on=True))
    history.set_bookmark(history.BookmarkBody(sessionId=A, on=False))
    with open(os.path.join(TMP, "session-bookmarks.json"), encoding="utf-8") as f:
        cur = json.load(f)
    assert A not in cur and B in cur
    assert not os.path.exists(os.path.join(TMP, "tmp-session-bookmarks.tmp"))
    assert [(k["session"], k["on"]) for k in seen] == [(B, True), (A, False)]


def test_etag_changes_when_side_file_changes():
    setup()
    e1 = history.index_etag()
    p = os.path.join(TMP, "session-tags.json")
    os.utime(p, (os.path.getmtime(p) + 5,) * 2)
    assert history.index_etag() != e1


def test_live_maps_both_ids():
    history.set_context(lambda: {}, lambda **kw: None, lambda: {"tabs": [
        {"surfaceId": "S1", "sessionId": "OLD-ID", "liveSessionId": "new-id", "status": "running"}]})
    s = history.live()["sessions"]
    assert s["old-id"]["surfaceId"] == "S1" and s["new-id"]["surfaceId"] == "S1"


def test_lines_backward_matches_split_and_skips_big_lines():
    import random
    import transcript
    rnd = random.Random(7)
    p = os.path.join(TMP, "lb.jsonl")
    lines = [b"x" * rnd.choice([0, 1, 5, 300, 70_000, 150_000, 600_000]) for _ in range(60)]
    with open(p, "wb") as f:
        f.write(b"\n".join(lines) + b"\n")
    size = os.path.getsize(p)
    want, o = [], 0                                            # 정답: 단순 split 의 (시작, 줄) — 빈 줄 제외
    for ln in lines:
        if ln.strip():
            want.append((o, ln))
        o += len(ln) + 1
    want.reverse()
    got = [(off, raw) for off, raw, _ in transcript._lines_backward(p, size)]
    assert got == want                                         # 조각(256KB) 경계를 넘는 줄 포함
    cap = 100_000                                              # 150KB(조각 안)·600KB(조각 넘김) 둘 다 None
    got2 = list(transcript._lines_backward(p, size, line_cap=cap))
    assert [off for off, _, _ in got2] == [off for off, _ in want]
    for (off, raw, n), (_, ln) in zip(got2, want):
        assert (raw is None) == (len(ln) > cap) and n == len(ln)


def test_read_page_big_user_line_becomes_chip():
    import transcript
    p = os.path.join(TMP, "big.jsonl")
    big = {"type": "user", "uuid": "0003cccc-0000", "message": {"role": "user", "content": "y" * 200}}
    with open(p, "w", encoding="utf-8") as f:
        f.write(json.dumps({"type": "user", "uuid": "0001aaaa-0000", "message": {"role": "user", "content": "작은 질문"}}) + "\n")
        f.write(json.dumps({"type": "progress", "data": "z" * 300}) + "\n")
        f.write(json.dumps(big) + "\n")
    old = transcript.LINE_CAP
    transcript.LINE_CAP = 150                                   # 기본 인자는 정의 때 굳으니 호출 경로로 바꾼다
    try:
        items = [i for off, raw, n in transcript._lines_backward(p, os.path.getsize(p), line_cap=150)
                 if raw is None for i in [transcript._big_line_item(open(p, "rb"), off, n)]]
    finally:
        transcript.LINE_CAP = old
    assert len(items) == 2 and items[0]["kind"] == "system" and "사람 메시지" in items[0]["text"]
    assert items[1] is None                                    # progress 는 조용히 버린다


def test_wanted_keeps_assistant_lines_whose_message_key_comes_first():
    import transcript
    asst = b'{"parentUuid":"p","isSidechain":false,"message":{"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"hi"}]},"type":"assistant","uuid":"u"}'
    user = b'{"parentUuid":"p","isSidechain":false,"type":"user","message":{"role":"user","content":"q"}}'
    prog = b'{"parentUuid":"p","isSidechain":false,"type":"progress","data":{"type":"user"}}'
    side = b'{"parentUuid":"p","isSidechain":true,"type":"user","message":{"role":"user","content":"q"}}'
    assert transcript._wanted(asst) and transcript._wanted(user)
    assert not transcript._wanted(prog) and not transcript._wanted(side)
