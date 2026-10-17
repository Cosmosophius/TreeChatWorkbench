#!/usr/bin/env python3
"""
TalkTree 缓存前缀优化的**实测**代理。

作用：把 App 的请求原样转发给上游 OpenAI 兼容端点，同时把
  · 每一轮的完整请求体（落盘为 req_<n>.json）
  · 每一轮的 usage（含 prompt_cache_hit_tokens / prompt_cache_miss_tokens）
记录下来，用于验证：
  1. RAG 知识是否真的落在消息序列**末尾**、system prompt 是否稳定（改动 1）
  2. originQuote 是否只出现一次（改动 2）
  3. 逐轮的缓存命中 token 是否上升（改动的目标本身）

只依赖标准库；SSE 逐块透传，不缓冲，保证 App 的流式输出与真机体验一致。
"""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request as urlreq
from urllib.error import HTTPError

UPSTREAM = os.environ.get("UPSTREAM", "http://10.4.76.130:8765")
LISTEN_PORT = int(os.environ.get("PORT", "8770"))
OUT_DIR = os.environ.get("OUT_DIR", "/tmp/proxy_reqs")
os.makedirs(OUT_DIR, exist_ok=True)

_lock = threading.Lock()
_counter = {"n": 0}


def next_index() -> int:
    with _lock:
        _counter["n"] += 1
        return _counter["n"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音默认日志，避免刷屏
        pass

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        idx = next_index()

        # ---- 记录请求体 ----
        req_path = os.path.join(OUT_DIR, f"req_{idx:03d}.json")
        with open(req_path, "wb") as f:
            f.write(body)
        # 先无条件记录方法与路径（含非 JSON 请求，如 GET /v1/models 的连通性测试）
        print(f"\n=== [{idx:03d}] {self.command} {self.path} "
              f"len={length} auth={'yes' if self.headers.get('Authorization') else 'no'}")
        try:
            parsed = json.loads(body.decode("utf-8"))
            msgs = parsed.get("messages") or []
            sys_msg = parsed.get("system") or parsed.get("systemPrompt")
            print(f"    model={parsed.get('model')} messages={len(msgs)} "
                  f"stream={parsed.get('stream')}")
            if sys_msg:
                print(f"    systemPrompt 长度={len(str(sys_msg))}")
            for i, m in enumerate(msgs):
                content = m.get("content")
                if isinstance(content, list):
                    content = "".join(
                        part.get("text", "") for part in content if isinstance(part, dict)
                    )
                content = content or ""
                print(f"    [{i}] {m.get('role'):9s} len={len(content):6d}  {content[:70]!r}")
            with open(req_path + ".summary.txt", "w") as f:
                f.write(f"messages={len(msgs)} stream={parsed.get('stream')}\n")
                if sys_msg:
                    f.write(f"systemPrompt(len={len(str(sys_msg))}):\n{sys_msg}\n\n")
                for i, m in enumerate(msgs):
                    c = m.get("content")
                    if isinstance(c, list):
                        c = "".join(p.get("text", "") for p in c if isinstance(p, dict))
                    f.write(f"--- [{i}] {m.get('role')} (len={len(c or '')}) ---\n{c or ''}\n")
        except Exception as e:  # 记录失败不能影响转发
            print(f"    (请求体解析失败: {e})", file=sys.stderr)

        # ---- 转发 ----
        url = UPSTREAM.rstrip("/") + self.path
        # ⚠️ 必须剔除 Accept-Encoding：客户端（OkHttp）默认带 gzip，
        # 上游压缩后本代理只透传 Content-Type、不透传 Content-Encoding，
        # App 会把压缩字节当明文 SSE 解析 → 表现为"有响应但（无输出）"。
        # 让上游返回 identity 编码，问题消失。
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in ("host", "content-length", "connection",
                                        "accept-encoding")}
        req = urlreq.Request(url, data=body, headers=headers, method=self.command)
        try:
            resp = urlreq.urlopen(req, timeout=300)
        except HTTPError as e:
            data = e.read()
            self.send_response(e.code)
            for k, v in e.headers.items():
                if k.lower() in ("content-type", "cache-control"):
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            print(f"    ← 上游 HTTP {e.code} body={data[:200]!r}")
            return

        self.send_response(resp.status)
        ctype = resp.headers.get("Content-Type", "application/json")
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        usage_log = []
        # 按行透传（SSE 本就是"一行一个事件"）：既保持实时，又不会重复写字节。
        # 之前逐字节透传、遇到换行又把整行重写一遍，内容会翻倍。
        while True:
            raw = resp.readline()
            if not raw:
                break
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("data: ") and line != "data: [DONE]":
                try:
                    obj = json.loads(line[6:])
                    u = obj.get("usage")
                    if u:
                        usage_log.append(u)
                except Exception:
                    pass
            try:
                self.wfile.write(raw)
                self.wfile.flush()
            except Exception:
                break
        if usage_log:
            u = usage_log[-1]
            hit = u.get("prompt_cache_hit_tokens")
            miss = u.get("prompt_cache_miss_tokens")
            pt = u.get("prompt_tokens")
            ct = u.get("completion_tokens")
            print(f"    ← usage: prompt={pt} completion={ct} "
                  f"cache_hit={hit} cache_miss={miss}")
            with open(os.path.join(OUT_DIR, "usage_log.jsonl"), "a") as f:
                f.write(json.dumps({"idx": idx, "usage": u}, ensure_ascii=False) + "\n")
        else:
            print("    ← 无 usage（可能非流式或解析失败）")

    do_POST = _proxy
    do_GET = _proxy


if __name__ == "__main__":
    print(f"录制代理启动：: {LISTEN_PORT} → {UPSTREAM}，请求体写入 {OUT_DIR}")
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler).serve_forever()
