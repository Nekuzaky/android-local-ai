import argparse
import json
import struct
import threading
import time
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_BODY = 32 * 1024 * 1024
MODELS = ["fake-llama:latest", "fake-vision:latest"]
CHECKPOINTS = ["fake_checkpoint.safetensors"]
HISTORY = {}
HOSTILE = False
MODE = "normal"


def tiny_png():
    width = 64
    height = 64
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw += bytes(((x * 4) % 256, (y * 4) % 256, 160))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")


class Base(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("%s %s" % (self.__class__.__name__, fmt % args), flush=True)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length < 0 or length > MAX_BODY:
            return None
        return self.rfile.read(length)

    def send_json(self, value, code=200):
        data = json.dumps(value).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_bytes(self, data, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def redirect_out(self):
        self.send_response(302)
        self.send_header("Location", "http://example.com/stolen")
        self.send_header("Content-Length", "0")
        self.end_headers()


def drip(handler):
    handler.send_response(200)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Connection", "close")
    handler.end_headers()
    handler.close_connection = True
    try:
        for _ in range(600):
            handler.wfile.write(b" ")
            handler.wfile.flush()
            time.sleep(5)
    except OSError:
        return


def huge(handler):
    handler.send_response(200)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Connection", "close")
    handler.end_headers()
    handler.close_connection = True
    try:
        handler.wfile.write(b'{"models":[' + b'{"name":"x"},' * 4_000_000)
    except OSError:
        return


class FakeOllama(Base):
    def do_GET(self):
        if MODE == "stall":
            drip(self)
            return
        if MODE == "huge":
            huge(self)
            return
        if self.path == "/api/tags":
            if HOSTILE:
                self.send_json({"models": [{"name": "ok:latest"}, {"name": "../../etc/passwd\n"}, {"name": 7}]})
            else:
                self.send_json({"models": [{"name": name} for name in MODELS]})
            return
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        if self.path != "/api/chat":
            self.send_json({"error": "not found"}, 404)
            return
        raw = self.body()
        if raw is None:
            self.send_json({"error": "too large"}, 413)
            return
        try:
            request = json.loads(raw)
            last = request["messages"][-1]
        except (ValueError, KeyError, IndexError, TypeError):
            self.send_json({"error": "bad request"}, 400)
            return
        if HOSTILE:
            self.redirect_out()
            return
        if MODE == "stall":
            drip(self)
            return
        if MODE == "huge":
            self.send_response(200)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                self.wfile.write(b'{"message":{"content":"' + b"A" * (64 * 1024 * 1024))
            except OSError:
                pass
            return
        images = last.get("images") or []
        text = "Echo from fake Ollama: " + str(last.get("content", ""))[:200]
        if images:
            text += " (saw %d image, %d base64 chars)" % (len(images), len(images[0]))
        text += "\n```python\nprint('hello')\n```\nhttps://example.com is plain text here."
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        for word in text.split(" "):
            line = {"message": {"role": "assistant", "content": word + " "}, "done": False}
            self.wfile.write(json.dumps(line).encode("utf-8") + b"\n")
            self.wfile.flush()
            time.sleep(0.05)
        self.wfile.write(json.dumps({"message": {"role": "assistant", "content": ""}, "done": True}).encode("utf-8") + b"\n")


class FakeComfy(Base):
    def do_GET(self):
        parsed = urlparse(self.path)
        if MODE == "stall":
            drip(self)
            return
        if MODE == "huge" and parsed.path.startswith("/history/"):
            self.send_response(200)
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                self.wfile.write(b"[" + b"0," * 8_000_000 + b"0]")
            except OSError:
                pass
            return
        if parsed.path == "/object_info/CheckpointLoaderSimple":
            info = {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [CHECKPOINTS]}}}}
            self.send_json(info)
            return
        if parsed.path.startswith("/history/"):
            prompt_id = parsed.path[len("/history/"):]
            entry = HISTORY.get(prompt_id)
            if entry is None or time.time() < entry["ready_at"]:
                self.send_json({})
                return
            filename = "../../evil.png" if HOSTILE else "LocalMind_00001_.png"
            self.send_json({
                prompt_id: {
                    "status": {"status_str": "success", "completed": True},
                    "outputs": {"9": {"images": [{"filename": filename, "subfolder": "", "type": "output"}]}},
                }
            })
            return
        if parsed.path == "/view":
            query = parse_qs(parsed.query, keep_blank_values=True)
            if query.get("filename") != ["LocalMind_00001_.png"] or query.get("type") != ["output"]:
                self.send_json({"error": "bad file"}, 404)
                return
            self.send_bytes(tiny_png(), "image/png")
            return
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        if self.path != "/prompt":
            self.send_json({"error": "not found"}, 404)
            return
        raw = self.body()
        try:
            request = json.loads(raw)
            workflow = request["prompt"]
            node = workflow["6"]["inputs"]["text"]
        except (ValueError, KeyError, TypeError):
            self.send_json({"error": "bad workflow"}, 400)
            return
        print("FakeComfy prompt: %r" % node, flush=True)
        prompt_id = str(uuid.uuid4())
        HISTORY[prompt_id] = {"ready_at": time.time() + 2}
        self.send_json({"prompt_id": prompt_id, "number": 1})


def serve(handler, port):
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def main():
    global HOSTILE, MODE
    parser = argparse.ArgumentParser(description="Fake Ollama and ComfyUI for LocalMind tests. Binds to 127.0.0.1 only.")
    parser.add_argument("--ollama-port", type=int, default=11434)
    parser.add_argument("--comfy-port", type=int, default=8188)
    parser.add_argument("--hostile", action="store_true", help="answer with redirects, bad names and path traversal")
    parser.add_argument("--mode", choices=["normal", "huge", "stall"], default="normal", help="huge sends oversized bodies, stall drips one byte every 5 seconds")
    parser.add_argument("--seconds", type=float, default=0, help="stop after this many seconds, 0 runs until Ctrl+C")
    arguments = parser.parse_args()
    HOSTILE = arguments.hostile
    MODE = arguments.mode
    serve(FakeOllama, arguments.ollama_port)
    serve(FakeComfy, arguments.comfy_port)
    print("fake Ollama on 127.0.0.1:%d, fake ComfyUI on 127.0.0.1:%d" % (arguments.ollama_port, arguments.comfy_port), flush=True)
    print("adb reverse tcp:%d tcp:%d" % (arguments.ollama_port, arguments.ollama_port), flush=True)
    print("adb reverse tcp:%d tcp:%d" % (arguments.comfy_port, arguments.comfy_port), flush=True)
    try:
        if arguments.seconds > 0:
            time.sleep(arguments.seconds)
        else:
            while True:
                time.sleep(3600)
    except KeyboardInterrupt:
        return


if __name__ == "__main__":
    main()
