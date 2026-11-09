#!/usr/bin/env python3
"""Minimal LSP client for poking faces-ls from the command line.

Usage:
  lsp-client.py FILE REQUEST LINE CHARACTER [--replace OLD NEW]

REQUEST is completion, hover or definition; LINE and CHARACTER are 0-based. --replace edits the file text
before it is sent (the file on disk is not changed). The workspace root is the nearest directory with a pom.xml.
FACES_JDT_RENDEZVOUS is passed through, so the jdtls of a running editor is used when set.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "faces-ls/target/faces-ls.jar"


def send(proc, message):
    body = json.dumps(message).encode()
    proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    proc.stdin.flush()


def read(proc):
    headers = {}
    while True:
        line = proc.stdout.readline().decode().strip()
        if not line:
            break
        key, value = line.split(":", 1)
        headers[key.lower()] = value.strip()
    return json.loads(proc.stdout.read(int(headers["content-length"])))


def request(proc, ident, method, params):
    send(proc, {"jsonrpc": "2.0", "id": ident, "method": method, "params": params})
    while True:
        message = read(proc)
        if message.get("id") == ident and "method" not in message:
            return message
        if "method" in message and "id" in message:
            send(proc, {"jsonrpc": "2.0", "id": message["id"], "result": None})
        elif message.get("method") == "textDocument/publishDiagnostics":
            for d in message["params"]["diagnostics"]:
                r = d["range"]["start"]
                print(f"diagnostic {r['line']}:{r['character']} {d['message']}", file=sys.stderr)


def main():
    args = sys.argv[1:]
    replace = None
    if "--replace" in args:
        i = args.index("--replace")
        replace = (args[i + 1], args[i + 2])
        args = args[:i] + args[i + 3:]
    file, method, line, character = Path(args[0]).resolve(), args[1], int(args[2]), int(args[3])
    root = next((p for p in file.parents if (p / "pom.xml").exists()), file.parent)
    text = file.read_text()
    if replace:
        text = text.replace(replace[0], replace[1], 1)

    proc = subprocess.Popen(["java", "-jar", str(JAR)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, env=os.environ.copy())
    request(proc, 1, "initialize", {"processId": os.getpid(), "rootUri": root.as_uri(),
                                    "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
                                    "capabilities": {}})
    send(proc, {"jsonrpc": "2.0", "method": "initialized", "params": {}})
    uri = file.as_uri()
    send(proc, {"jsonrpc": "2.0", "method": "textDocument/didOpen",
                "params": {"textDocument": {"uri": uri, "languageId": "facelets", "version": 1, "text": text}}})
    if os.environ.get("FACES_JDT_RENDEZVOUS"):
        time.sleep(3)  # let the jdtls connection come up
    result = request(proc, 2, "textDocument/" + method,
                     {"textDocument": {"uri": uri}, "position": {"line": line, "character": character}})
    print(json.dumps(result.get("result"), indent=2))
    request(proc, 3, "shutdown", None)
    send(proc, {"jsonrpc": "2.0", "method": "exit"})
    proc.wait(timeout=5)


if __name__ == "__main__":
    main()
