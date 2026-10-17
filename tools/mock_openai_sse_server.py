"""假端点：记录收到的请求体，并流式返回一段短文本（用于验证 prompt 内容）。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, time

TEXT = "MOCK-OK received."

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            obj = json.loads(raw.decode("utf-8", "replace"))
            msgs = obj.get("messages") or []
            sysp = obj.get("system") or obj.get("systemPrompt") or ""
            print(f"\n=== 请求 messages={len(msgs)} 独立system={len(str(sysp))} 字节", flush=True)
            for i, m in enumerate(msgs):
                c = m.get("content")
                if not isinstance(c, str):
                    c = json.dumps(c, ensure_ascii=False)
                print(f"   [{i}] {m.get('role'):9s} len={len(c):5d} {c[:100]!r}", flush=True)
        except Exception as e:
            print("请求体解析失败:", e, flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def chunk(o):
            self.wfile.write(("data: " + json.dumps(o) + "\n\n").encode())
            self.wfile.flush()

        chunk({"id": "m", "object": "chat.completion.chunk", "model": "mock",
               "choices": [{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None}]})
        for i in range(0, len(TEXT), 6):
            chunk({"id": "m", "object": "chat.completion.chunk", "model": "mock",
                   "choices": [{"index": 0, "delta": {"content": TEXT[i:i+6]}, "finish_reason": None}]})
            time.sleep(0.05)
        chunk({"id": "m", "object": "chat.completion.chunk", "model": "mock",
               "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
               "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}})
        try:
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
        except Exception:
            pass

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": "mock", "object": "model"}]}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

ThreadingHTTPServer(("0.0.0.0", 8771), H).serve_forever()
