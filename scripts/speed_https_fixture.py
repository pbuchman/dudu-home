"""Loopback-only HTTPS fixture for the Android speed instrumentation."""

from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import ssl
import threading
import time
from urllib.parse import parse_qs, urlsplit


LIMIT = 16 * 1024 * 1024
BLOCK = os.urandom(32 * 1024)


@dataclass
class Scenario:
    name: str
    counts: dict[str, int] = field(default_factory=lambda: dict(ping=0, down=0, up=0,
                                                                 down_bytes=0, up_bytes=0, rejected=0))


class Fixture(ThreadingHTTPServer):
    daemon_threads = False  # Flush delayed socket outcomes before closing the trace.

    def __init__(self, cert, key, trace):
        super().__init__(("127.0.0.1", 0), Handler)
        self.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.tls.minimum_version = ssl.TLSVersion.TLSv1_2
        self.tls.load_cert_chain(cert, key)
        self.trace = trace
        self.lock = threading.Lock()
        self.scenario = Scenario("ok")

    def finish_request(self, request, address):
        # TLS handshakes run on the worker so an abandoned connection cannot stall the fixture.
        request.settimeout(15)
        try:
            with self.tls.wrap_socket(request, server_side=True) as channel:
                super().finish_request(channel, address)
        except (OSError, TimeoutError):
            pass

    def set_mode(self, name):
        with self.lock:
            self.scenario = Scenario(name)

    def status(self):
        with self.lock:
            return self.scenario.counts.copy()

    def request_sample(self, side, size):
        with self.lock:
            scenario = self.scenario
            scenario.counts[side] += 1
            field_name = side + "_bytes"
            if size and scenario.counts[field_name] + size > LIMIT:
                scenario.counts["rejected"] += 1
                return scenario, False
            if size:
                scenario.counts[field_name] += size
            return scenario, True

    def record(self, scenario, side, size, delivered, outcome):
        with self.lock:
            print(f"{scenario.name} {side} requested_bytes={size} delivered_bytes={delivered} outcome={outcome}",
                  file=self.trace, flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def respond(self, status, size=0):
        self.send_response(status)
        self.send_header("Access-Control-Allow-Origin", "https://speed-test.dudu.invalid")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Timing-Allow-Origin", "https://speed-test.dudu.invalid")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        if status == 429:
            self.send_header("Retry-After", "2")
            self.send_header("Access-Control-Expose-Headers", "Retry-After")
        self.end_headers()

    def do_OPTIONS(self):
        self.respond(204 if urlsplit(self.path).path in {"/__down", "/__up"} else 404)

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/control/status":
            payload = json.dumps(self.server.status()).encode()
            self.respond(200, len(payload))
            self.wfile.write(payload)
            return
        if url.path.startswith("/control/"):
            mode = url.path.removeprefix("/control/")
            if mode not in {"ok", "slow", "slow-total", "rate", "budget-down", "budget-up", "cancel", "retry",
                            "takeover", "automation", "switch", "offline"}:
                self.respond(404)
                return
            self.server.set_mode(mode)
            self.respond(204)
            return
        if url.path != "/__down":
            self.respond(404)
            return
        try:
            size = int(parse_qs(url.query)["bytes"][0])
        except (KeyError, IndexError, ValueError):
            self.respond(400)
            return
        self.sample("down" if size else "ping", size)

    def do_POST(self):
        if urlsplit(self.path).path != "/__up":
            self.respond(404)
            return
        try:
            size = int(self.headers["Content-Length"])
        except (TypeError, ValueError):
            self.respond(411)
            return
        self.sample("up", size)

    def sample(self, side, size):
        if size < 0 or size > LIMIT:
            self.respond(413)
            return
        scenario, within_limit = self.server.request_sample(side, size)
        done, outcome = 0, "ok"
        try:
            limited = scenario.name == "rate" or (scenario.name == "budget-" + side and scenario.counts[side] >= 5)
            if not within_limit:
                outcome = "over_budget"
                self.respond(413)
            elif limited and side != "up":
                outcome = "rate_limited"
                self.respond(429)
            else:
                time.sleep(0.05)
                if scenario.name == "slow-total":
                    time.sleep(8 if side == "ping" else 7)
                if side == "down" and scenario.name in {"slow", "cancel", "takeover", "automation", "switch"}:
                    time.sleep(13 if scenario.name == "slow" else 4)
                if side == "up":
                    while done < size:
                        block = self.rfile.read(min(len(BLOCK), size - done))
                        if not block:
                            raise ConnectionError
                        done += len(block)
                        time.sleep(0.005)
                    if limited:
                        outcome = "rate_limited"
                    self.respond(429 if limited else 200)
                else:
                    self.respond(200, size)
                    while done < size:
                        block = BLOCK[:min(len(BLOCK), size - done)]
                        self.wfile.write(block)
                        done += len(block)
                        time.sleep(0.005)
        except OSError:
            outcome = "disconnected"
        finally:
            self.server.record(scenario, side, size, done, outcome)
