"""Dev-only mock of the Monarch `weekly_spend` endpoint for testing the widget's on-device sync.

Serves the tool's JSON shape on any path. Change SPENT_CENTS to prove a sync actually landed.
Run: python tools/mock_weekly_spend.py  (emulator reaches it at http://10.0.2.2:8799/weekly_spend)
"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SPENT_CENTS = 8000  # $80.00 spent -> $175 - $80 = $95 remaining ($95 on the ring)
AUTH_OK = True
PENDING_INCLUDED = True


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = {
            "spent_cents": SPENT_CENTS,
            "spent": SPENT_CENTS / 100.0,
            "as_of_ms": int(time.time() * 1000),
            "auth_ok": AUTH_OK,
            "pending_included": PENDING_INCLUDED,
            "week_start": "2026-08-31",
            "week_end": "2026-09-06",
        }
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("REQ", self.path, "->", SPENT_CENTS, flush=True)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8799), Handler).serve_forever()
