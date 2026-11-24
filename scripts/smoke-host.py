"""Exercise the Windows binary from an isolated, disposable host configuration."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
from contextlib import asynccontextmanager
import uuid

from websockets.exceptions import ConnectionClosed
from websockets.asyncio.client import connect


async def exchange(socket, **request):
    await socket.send(json.dumps(request))
    return json.loads(await asyncio.wait_for(socket.recv(), 4))


def identity(process):
    for line in process.stdout:
        match = re.search(rb"([0-9a-f]{64})\.([A-Za-z0-9_-]{32})", line)
        if match:
            return tuple(part.decode("ascii") for part in match.groups())
    raise RuntimeError("Host pairing code was unavailable")


@asynccontextmanager
async def dial(fingerprint):
    # Verify the exact freshly generated certificate before sending any application data.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    for attempt in range(30):
        try:
            socket = await connect("wss://127.0.0.1:43851/v1", ssl=context, proxy=None, open_timeout=5, close_timeout=1)
            actual = hashlib.sha256(socket.transport.get_extra_info("ssl_object").getpeercert(binary_form=True)).hexdigest()
            if actual != fingerprint:
                await socket.close()
                raise ssl.SSLError("wrong host certificate")
            break
        except ConnectionRefusedError:
            if attempt == 29:
                raise
            await asyncio.sleep(0.1)
    try:
        yield socket
    finally:
        await socket.close()


async def run(binary):
    with tempfile.TemporaryDirectory(prefix="kenshixdeck-smoke-") as folder:
        environment = os.environ | {"LOCALAPPDATA": folder}
        host = subprocess.Popen([binary, "--pair", "--headless"], env=environment, stdout=subprocess.PIPE)
        try:
            fingerprint, code = identity(host)
            try:
                async with dial("0" * 64):
                    pass
            except ssl.SSLError:
                pass
            else:
                raise AssertionError("A different pin was accepted")
            async with dial(fingerprint) as socket:
                bad = await exchange(socket, type="hello", version=1, token="wrong")
                assert bad.get("code") == "unauthorized", bad
            async with dial(fingerprint) as socket:
                paired = await exchange(socket, type="pair", version=1, code=code)
                assert paired.get("type") == "paired", paired
                token = paired["token"]
            async with dial(fingerprint) as socket:
                replay = await exchange(socket, type="pair", version=1, code=code)
                assert replay.get("code") == "pair_denied", replay

            async with dial(fingerprint) as socket:
                empty = await exchange(socket, type="hello", version=1, token=token)
                assert empty.get("deck") == {"revision": 0, "buttons": []}, empty
                button = {"id": str(uuid.uuid4()), "label": "Ses", "action": "mixer"}
                deck = {"revision": 0, "buttons": [button]}
                saved = await exchange(socket, type="save", deck=deck)
                assert saved.get("deck") == {"revision": 1, "buttons": [button]}, saved
                assert (await exchange(socket, type="save", deck=deck)).get("type") == "conflict"
                profile, page, folder = (str(uuid.uuid4()) for _ in range(3))
                layout = {
                    "panels": [
                        {"id": profile, "name": "Ev", "parent": None, "kind": "profile"},
                        {"id": page, "name": "İş", "parent": profile, "kind": "page"},
                        {"id": folder, "name": "Ses", "parent": page, "kind": "folder"},
                    ],
                    "placements": {button["id"]: {"panel": folder, "span": 2, "color": "#143731"}},
                }
                updated = await exchange(socket, type="save", deck={
                    "revision": 1, "buttons": [button], "layout": layout})
                assert updated.get("deck", {}).get("layout") == layout, updated
                await exchange(socket, type="metrics")
                await asyncio.sleep(0.2)
                metrics = (await exchange(socket, type="metrics"))["measurements"]
                assert 0 <= metrics["cpu_percent"] <= 100
                for resource in ("memory", "system_disk"):
                    reading = metrics[resource]
                    assert reading and 0 <= reading["used"] <= reading["total"]
                print("Nested panel layout and real Windows CPU/RAM/disk readings passed.")
                missing = await exchange(socket, type="execute", operation=str(uuid.uuid4()), button=str(uuid.uuid4()))
                assert missing.get("status") == "failed", missing
                result = await exchange(socket, type="mixer")
                assert result.get("type") == "mixer", result
                levels = result["levels"]
                master = next(level for level in levels if level["id"] == "master")
                assert 0 <= master["percent"] <= 100 and isinstance(master["muted"], bool), master
                print("Core Audio read succeeded for", len(levels), "real control rows.")
                await socket.send(" " * 70000)
                try:
                    await asyncio.wait_for(socket.recv(), 4)
                except ConnectionClosed:
                    pass
                else:
                    raise AssertionError("Oversized input did not close the session")

            args = [binary, "--revoke", "--headless"]
            subprocess.run(args, env=environment, stdout=subprocess.DEVNULL, check=True)
            async with dial(fingerprint) as socket:
                assert (await exchange(socket, type="hello", version=1, token=token)).get("code") == "unauthorized"
        finally:
            host.terminate()
            host.wait(timeout=5)
            host.stdout.close()
        print("WSS, pin, token, one-use pairing, revision conflict, size limit and revoke passed.")


if __name__ == "__main__":
    asyncio.run(run(str(Path(sys.argv[1]).resolve(strict=True))))
