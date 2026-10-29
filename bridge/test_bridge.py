import copy
import json
import threading
import unittest
import urllib.request
from bridge import BridgeState, make_server, RpcSession


def record():
    return dict(id="00000000-0000-0000-0000-000000000001", title="Kule", url="", lastChapter="10", status="READING", opinion="LIKED", likedReason="Dünya", dislikedReason="", dropReason="", notes="Korunmalı", createdAt=1, updatedAt=2)


def snapshot(records):
    return dict(format="manhwa-takip", version=1, exportedAt=3, records=records)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.state = BridgeState("test-pairing-key-only-for-tests-1234567890")
        self.state.sync(dict(library=snapshot([record()]), decisions={}))

    def test_propose_never_mutates_snapshot_and_pins_before(self):
        result = self.state.call("propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=dict(lastChapter="20")))
        self.assertEqual("pending_phone_approval", result["status"])
        self.assertEqual("10", self.state.call("list_library", {})["records"][0]["lastChapter"])
        pending = self.state.sync(dict(library=snapshot([record()]), decisions={}))["proposals"][0]
        self.assertEqual("10", pending["before"]["lastChapter"])
        self.assertEqual("20", pending["after"]["lastChapter"])
        self.assertEqual("Korunmalı", pending["after"]["notes"])

    def test_stale_snapshot_invalid_field_type_and_secret_are_rejected(self):
        for fields in [dict(lastChapter=20), dict(apiKey="no"), dict(status="UNKNOWN"), dict(lastChapter="-1")]:
            with self.assertRaises(ValueError):
                self.state.call("propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=fields))
        with self.assertRaises(ValueError):
            self.state.call("propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=1, fields=dict(notes="x")))
        self.assertEqual([], self.state.sync(dict(library=snapshot([record()]), decisions={}))["proposals"])

    def test_android_incompatible_url_and_utf16_limits_never_enter_queue(self):
        for fields in [dict(url="https://example.com/a b"), dict(url="https://foo_bar.test"), dict(title="🐉" * 200), dict(notes="🐉" * 3000), dict(url="https://foo.123"), dict(url="https://999.999.999.999"), dict(url="https://example.com/[1]")]:
            with self.assertRaises(ValueError):
                self.state.call("propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=fields))
        self.assertEqual([], self.state.sync(dict(library=snapshot([record()]), decisions={}))["proposals"])

    def test_bad_batch_is_atomic_and_disconnect_and_expiry_block_access(self):
        bad = record(); bad["title"] = ""
        with self.assertRaises(ValueError):
            self.state.sync(dict(library=snapshot([record(), bad]), decisions={}))
        self.assertEqual(1, len(self.state.call("list_library", {})["records"]))
        self.state.expires = 0
        with self.assertRaises(ValueError): self.state.call("list_library", {})
        self.state.disconnect()
        self.assertEqual([], self.state.records)

    def test_rejection_and_approval_ack_are_idempotent(self):
        token = self.state.call("propose_change", dict(seriesId=None, fields=dict(title="Yeni")))["token"]
        self.state.sync(dict(library=snapshot([record()]), decisions={token:"rejected"}))
        self.state.sync(dict(library=snapshot([record()]), decisions={token:"rejected"}))
        self.assertEqual("rejected", self.state.call("proposal_status", dict(token=token))["status"])
        self.assertEqual([], self.state.sync(dict(library=snapshot([record()]), decisions={}))["proposals"])

    def test_rpc_lifecycle_tools_and_error_do_not_echo_inputs(self):
        rpc = RpcSession(self.state)
        def request(method, params=None, id=1):
            return rpc.handle(dict(jsonrpc="2.0", id=id, method=method, params=params or {}))
        self.assertIn("error", request("tools/list"))
        init = request("initialize", dict(protocolVersion="2025-11-25", capabilities={}, clientInfo=dict(name="test", version="1")))
        self.assertEqual("2025-11-25", init["result"]["protocolVersion"])
        rpc.handle(dict(jsonrpc="2.0", method="notifications/initialized"))
        tools = request("tools/list")["result"]["tools"]
        self.assertEqual(["list_library", "propose_change", "proposal_status"], [t["name"] for t in tools])
        result = request("tools/call", dict(name="list_library", arguments={}))["result"]
        self.assertEqual("Kule", result["structuredContent"]["records"][0]["title"])
        bad = request("tools/call", dict(name="unexpected-secret-input", arguments={}))
        self.assertNotIn("unexpected-secret-input", json.dumps(bad))

    def test_http_auth_origin_and_real_sync(self):
        server = make_server(self.state, port=0)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        url = f"http://127.0.0.1:{server.server_port}/sync"
        def send(key=None, origin=None):
            headers = {"Content-Type":"application/json"}
            if key: headers["Authorization"] = f"Bearer {key}"
            if origin: headers["Origin"] = origin
            req = urllib.request.Request(url, json.dumps(dict(library=snapshot([record()]), decisions={})).encode(), headers)
            return urllib.request.urlopen(req, timeout=3)
        try:
            for key, origin in [(None, None), ("bad", None), (self.state.key, "https://evil.invalid")]:
                with self.assertRaises(urllib.error.HTTPError) as error: send(key, origin)
                try:
                    self.assertIn(error.exception.code, [401, 403])
                    error.exception.read()
                finally: error.exception.close()
            with send(self.state.key) as response:
                self.assertEqual([], json.load(response)["proposals"])
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == "__main__": unittest.main()
