#!/usr/bin/env python3
"""Kurzlebiger Senkbecken-Server: nimmt POST von localhost an und legt JSON in .secrets.json (600).
   Zweck: Zugangsdaten aus dem Browser in eine Datei bringen, ohne sie über das Gespräch zu schicken."""
import http.server, json, os, threading

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, ".secrets.json")


class Handler(http.server.BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n)
        data = json.loads(body.decode("utf-8"))
        with open(OUT, "w") as f:
            json.dump(data, f)
        os.chmod(OUT, 0o600)
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(("ok keys=%d" % len(data)).encode())
        threading.Thread(target=self.server.shutdown).start()

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", 39999), Handler).serve_forever()
print("sink beendet")
