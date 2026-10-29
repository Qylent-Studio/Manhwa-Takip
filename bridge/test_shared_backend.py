import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from bridge import BridgeState, RpcSession, make_server
from test_bridge import record, snapshot


class SharedBackendTests(unittest.TestCase):
    def test_second_listener_cannot_split_the_same_usb_port(self):
        first = make_server(BridgeState("fixture-only"), 0)
        second = None
        try:
            with self.assertRaises(OSError):
                second = make_server(BridgeState("fixture-only"), first.server_port)
        finally:
            if second is not None: second.server_close()
            first.server_close()

    def test_two_mcp_sessions_share_phone_snapshot_approval_rejection_and_close(self):
        from bridge import RemoteBridge
        state = BridgeState("fixture-only")
        server = make_server(state, 0)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        phone = RemoteBridge(state.key, server.server_port)
        clients = [RpcSession(RemoteBridge(state.key, server.server_port)) for _ in range(2)]
        def call(client, name, args=None):
            return client.handle(dict(jsonrpc="2.0", id=2, method="tools/call", params=dict(name=name, arguments=args or {})))["result"]
        try:
            for client in clients:
                client.handle(dict(jsonrpc="2.0", id=1, method="initialize", params=dict(protocolVersion="2025-11-25", capabilities={}, clientInfo={})))
                client.handle(dict(jsonrpc="2.0", method="notifications/initialized"))
                self.assertTrue(call(client, "list_library")["isError"])
            phone.post("/sync", dict(library=snapshot([record()]), decisions={}))
            for client in clients:
                self.assertEqual("10", call(client, "list_library")["structuredContent"]["records"][0]["lastChapter"])
            token = call(clients[0], "propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=dict(lastChapter="20")))["structuredContent"]["token"]
            self.assertEqual("pending_phone_approval", call(clients[1], "proposal_status", dict(token=token))["structuredContent"]["status"])
            self.assertEqual("10", state.records[0]["lastChapter"])
            changed = record(); changed["lastChapter"] = "20"; changed["updatedAt"] = 4
            phone.post("/sync", dict(library=snapshot([changed]), decisions={token:"approved"}))
            self.assertEqual("approved", call(clients[1], "proposal_status", dict(token=token))["structuredContent"]["status"])
            rejected = call(clients[1], "propose_change", dict(seriesId=changed["id"], expectedUpdatedAt=4, fields=dict(lastChapter="30")))["structuredContent"]["token"]
            phone.post("/sync", dict(library=snapshot([changed]), decisions={rejected:"rejected"}))
            self.assertEqual("rejected", call(clients[0], "proposal_status", dict(token=rejected))["structuredContent"]["status"])
            self.assertEqual("20", call(clients[0], "list_library")["structuredContent"]["records"][0]["lastChapter"])
            phone.post("/disconnect", {})
            self.assertTrue(all(call(client, "list_library")["isError"] for client in clients))
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_internal_endpoints_preserve_auth_origin_host_and_field_guards(self):
        state = BridgeState("fixture-only")
        state.sync(dict(library=snapshot([record()]), decisions={}))
        server = make_server(state, 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            for path in ("/health", "/tool", "/shutdown"):
                for headers, expected in (({}, 401), ({"Authorization":"Bearer wrong"}, 401), ({"Authorization":"Bearer fixture-only", "Origin":"https://example.invalid"}, 403), ({"Authorization":"Bearer fixture-only", "Host":"example.invalid"}, 403)):
                    request = urllib.request.Request(f"http://127.0.0.1:{server.server_port}{path}", b"{}", headers)
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        urllib.request.urlopen(request, timeout=2)
                    try:
                        self.assertEqual(expected, error.exception.code)
                        error.exception.read()
                    finally: error.exception.close()
            from bridge import RemoteBridge
            with self.assertRaises(ValueError):
                RemoteBridge(state.key, server.server_port).call("propose_change", dict(seriesId=None, fields=dict(title="Fixture", apiKey="fixture-secret")))
            self.assertEqual({}, state.proposals)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_expiry_purges_memory_without_another_tool_call(self):
        state = BridgeState("fixture-only")
        state.sync(dict(library=snapshot([record()]), decisions={}))
        state.expires = time.monotonic() - 1
        server = make_server(state, 0)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True); thread.start()
        try:
            deadline = time.monotonic() + 2
            while state.records and time.monotonic() < deadline: time.sleep(0.01)
            self.assertEqual([], state.records)
            self.assertEqual(0, state.expires)
        finally:
            server.shutdown(); server.server_close(); thread.join()


    def test_relay_never_follows_redirects_with_pairing_key(self):
        from bridge import RemoteBridge
        calls = []
        class Target(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                calls.append(True); self.send_response(200); self.end_headers(); self.wfile.write(b"{}")
        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        class Redirect(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.send_response(302); self.send_header("Location", f"http://127.0.0.1:{target.server_port}/"); self.end_headers()
        redirect = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in [target, redirect]]
        for thread in threads: thread.start()
        try:
            with self.assertRaises(ValueError): RemoteBridge("fixture-only", redirect.server_port).health()
            self.assertEqual([], calls)
        finally:
            for server in [target, redirect]: server.shutdown(); server.server_close()
            for thread in threads: thread.join()

    def test_authenticated_shutdown_clears_state_and_stops_server(self):
        from bridge import RemoteBridge
        state = BridgeState("fixture-only")
        state.sync(dict(library=snapshot([record()]), decisions={}))
        server = make_server(state, 0)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True); thread.start()
        try:
            self.assertTrue(RemoteBridge(state.key, server.server_port).post("/shutdown", {})["stopped"])
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
            self.assertEqual([], state.records)
            self.assertEqual({}, state.proposals)
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == "__main__": unittest.main()
