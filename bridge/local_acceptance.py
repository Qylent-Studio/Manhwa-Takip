"""Owned random-port process acceptance; never touches the production bridge."""
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from bridge import protect, RemoteBridge
from test_bridge import record, snapshot


def lines(process):
    output = queue.Queue()
    def read():
        for line in process.stdout: output.put(line)
        output.put(None)
    threading.Thread(target=read, daemon=True).start()
    return output


def receive(output):
    try: line = output.get(timeout=15)
    except queue.Empty: raise RuntimeError("Acceptance child response timed out.") from None
    if line is None: raise RuntimeError("Acceptance child exited without a response.")
    return json.loads(line)


def run(local_app_data):
    environment = dict(os.environ, LOCALAPPDATA=local_app_data)
    folder = Path(__file__).resolve().parent
    subprocess.run([sys.executable, str(folder / "bridge.py"), "--init"], env=environment,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   check=True, timeout=10)
    key = protect((Path(local_app_data) / "ManhwaTakip" / "bridge-key.dpapi").read_bytes(), True).decode()
    owned = []
    remote = None
    try:
        server_code = """import json, os
from bridge import BridgeState, make_server, protect, key_path
state = BridgeState(protect(key_path().read_bytes(), True).decode())
server = make_server(state, 0)
print(json.dumps(dict(port=server.server_port, pid=os.getpid())), flush=True)
try: server.serve_forever(poll_interval=0.1)
finally: state.disconnect(); server.server_close()
"""
        backend = subprocess.Popen([sys.executable, "-c", server_code], cwd=folder, env=environment,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        owned.append(backend)
        endpoint = receive(lines(backend))
        remote = RemoteBridge(key, endpoint["port"])
        assert remote.health()["pid"] == backend.pid
        clients = []
        for _ in range(2):
            client = subprocess.Popen([sys.executable, "-c",
                "import sys; from bridge import main; main(port=int(sys.argv.pop()))", str(endpoint["port"])],
                cwd=folder, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            owned.append(client); clients.append((client, lines(client)))
        def rpc(index, id, method, params=None):
            process, output = clients[index]
            request = dict(jsonrpc="2.0", method=method)
            if id is not None: request["id"] = id
            if params is not None: request["params"] = params
            process.stdin.write((json.dumps(request) + "\n").encode()); process.stdin.flush()
            if id is None: return
            response = receive(output)
            assert response.get("id") == id
            return response["result"]
        def call(index, id, name, arguments):
            return rpc(index, id, "tools/call", dict(name=name, arguments=arguments))
        for index in range(2):
            init = rpc(index, 1, "initialize", dict(protocolVersion="2025-11-25", capabilities={}, clientInfo=dict(name="acceptance", version="1")))
            assert init["protocolVersion"] == "2025-11-25"
            rpc(index, None, "notifications/initialized")
            assert len(rpc(index, 2, "tools/list")["tools"]) == 3
            assert call(index, 3, "list_library", {})["isError"]
        remote.post("/sync", dict(library=snapshot([record()]), decisions={}))
        for index in range(2):
            assert call(index, 4, "list_library", {})["structuredContent"]["records"][0]["lastChapter"] == "10"
        proposal = call(0, 5, "propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=dict(lastChapter="20")))["structuredContent"]
        fetched = remote.post("/sync", dict(library=snapshot([record()]), decisions={}))
        assert fetched["proposals"][0]["after"]["lastChapter"] == "20"
        assert fetched["proposals"][0]["before"]["lastChapter"] == "10"
        remote.post("/sync", dict(library=snapshot([record()]), decisions={proposal["token"]:"rejected"}))
        assert call(1, 6, "proposal_status", dict(token=proposal["token"]))["structuredContent"]["status"] == "rejected"
        approved = call(1, 7, "propose_change", dict(seriesId=record()["id"], expectedUpdatedAt=2, fields=dict(lastChapter="15")))["structuredContent"]
        changed = record(); changed["lastChapter"] = "15"; changed["updatedAt"] = 3
        remote.post("/sync", dict(library=snapshot([changed]), decisions={approved["token"]:"approved"}))
        assert call(0, 8, "proposal_status", dict(token=approved["token"]))["structuredContent"]["status"] == "approved"
        assert call(0, 9, "list_library", {})["structuredContent"]["records"][0]["lastChapter"] == "15"
        remote.post("/disconnect", {})
        for index in range(2): assert call(index, 10, "list_library", {})["isError"]
        assert remote.health()["pid"] == backend.pid
        for process, output in clients:
            process.stdin.close(); process.wait(timeout=10)
            assert process.returncode == 0
        remote.post("/shutdown", {}); backend.wait(timeout=10)
        assert backend.returncode == 0
        print("LOCAL_MCP_ACCEPTANCE_OK - isolated DPAPI, two real stdio clients, shared HTTP, approval/rejection, expiry gate and owned cleanup; not ChatGPT acceptance.")
    finally:
        if remote is not None:
            try: remote.post("/shutdown", {})
            except Exception: pass
        for process in reversed(owned):
            if process.stdin is not None and not process.stdin.closed:
                try: process.stdin.close()
                except OSError: pass
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=5)
            if process.stdout is not None: process.stdout.close()


def main():
    with tempfile.TemporaryDirectory(prefix="manhwa-mcp-acceptance-") as local_app_data:
        run(local_app_data)


if __name__ == "__main__": main()
