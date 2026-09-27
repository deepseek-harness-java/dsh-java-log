#!/usr/bin/env python3
"""Log Lens E2E: app API + AI stream tool calls.

Usage: python3 log_lens_e2e.py
Prereq: app on 127.0.0.1:8093, harness on 127.0.0.1:8090,
        plugin dsh-java-log-plugin installed & activated,
        demo log at ~/.dsh-java-log/demo/app.log
"""
import json
import os
import sys
import time
import urllib.request

APP = "http://127.0.0.1:8093"
LOG = os.path.expanduser("~/.dsh-java-log/demo/app.log")
results = []


def call(path, payload):
    req = urllib.request.Request(APP + path, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def stream_once(agent_id, message, log_path):
    body = json.dumps({"agentId": agent_id, "message": message, "logPath": log_path}).encode()
    req = urllib.request.Request(APP + "/api/assistant/stream", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    content, tools, events = "", [], []
    try:
        with urllib.request.urlopen(req, timeout=200) as resp:
            event, data_buf = "message", []
            for raw in resp.read().decode("utf-8", "ignore").splitlines() + [""]:
                if raw.startswith("event:"):
                    event = raw[6:].strip()
                elif raw.startswith("data:"):
                    data_buf.append(raw[5:].strip())
                elif not raw and data_buf:
                    try:
                        payload = json.loads("\n".join(data_buf))
                        events.append(event)
                        if event == "chunk":
                            content += payload.get("content", "")
                        if event == "step_break" and payload.get("toolName"):
                            tools.append(payload["toolName"])
                    except json.JSONDecodeError:
                        pass
                    event, data_buf = "message", []
    except Exception as e:  # noqa: BLE001
        content += f"\nSTREAM-ERROR: {e}"
    return content, sorted(set(tools)), events


def case(name):
    def deco(fn):
        def wrapped():
            start = time.time()
            try:
                detail = fn()
                results.append((name, True, detail, time.time() - start))
                print(f"PASS  {name}  ({time.time()-start:.1f}s)  {detail}")
            except AssertionError as e:
                results.append((name, False, str(e), time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {e}")
            except Exception as e:  # noqa: BLE001
                results.append((name, False, f"{type(e).__name__}: {e}", time.time() - start))
                print(f"FAIL  {name}  ({time.time()-start:.1f}s)  {type(e).__name__}: {e}")
        return wrapped
    return deco


@case("T1 级别统计")
def t1():
    status, body = call("/api/log/stats", {"path": LOG})
    assert status == 200, f"stats {status}: {body}"
    assert body.get("totalLines", 0) >= 20, f"totalLines={body.get('totalLines')}"
    assert body.get("levels", {}).get("ERROR", 0) >= 3, f"levels={body.get('levels')}"
    assert body.get("firstTimestamp") and body.get("lastTimestamp"), "missing time range"
    return f"lines={body['totalLines']} error={body['levels']['ERROR']}"


@case("T2 错误扫描与聚类")
def t2():
    status, body = call("/api/log/errors", {"path": LOG})
    assert status == 200, f"errors {status}: {body}"
    patterns = body.get("topPatterns", [])
    assert patterns, "no patterns"
    top = patterns[0]
    assert top["count"] >= 2 and "PaymentService" in top["pattern"], f"top={top}"
    return f"errorLines={body['errorLines']} top={top['count']}x PaymentService"


@case("T3 堆栈聚类")
def t3():
    status, body = call("/api/log/stacks", {"path": LOG})
    assert status == 200, f"stacks {status}: {body}"
    frames = {f["frame"]: f["count"] for f in body.get("hotFrames", [])}
    assert any("PaymentService.charge" in k for k in frames), f"frames={frames}"
    assert body.get("exceptionTypes"), "no exception types"
    return f"exceptions={len(body['exceptionTypes'])} hotFrames={len(frames)}"


@case("T4 时间线根因")
def t4():
    status, body = call("/api/log/timeline", {"path": LOG})
    assert status == 200, f"timeline {status}: {body}"
    assert body.get("file"), f"no file: {body}"
    assert body.get("firstErrorLine") == 6, f"firstErrorLine={body.get('firstErrorLine')}"
    ctx = body.get("context", [])
    assert len(ctx) >= 8, f"context={len(ctx)}"
    joined = "\n".join(row["text"] for row in ctx)
    assert "SocketTimeoutException" in joined or "gateway timeout" in joined, "root cause missing in context"
    return f"firstErrorLine=6 context={len(ctx)} rows"


@case("T5 AI 对话调用日志工具")
def t5():
    content, tools, events = stream_once("e2e-log-t5", "这个日志里主要是什么错误？用工具查证后一句话回答", LOG)
    assert any("log_" in t for t in tools), f"no log tool called, tools={tools}"
    assert "chunk" in events, f"no chunk events"
    assert len(content) > 20, f"content too short: {content[:80]}"
    return f"tools={tools} chars={len(content)}"


def main():
    t1(); t2(); t3(); t4(); t5()
    passed = sum(1 for _, ok, _, _ in results if ok)
    print(f"\n{passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
