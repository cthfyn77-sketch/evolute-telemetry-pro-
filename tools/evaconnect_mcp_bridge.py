#!/usr/bin/env python3
"""Loopback-only HTTP adapter for the official evaconnect-mcp stdio server.

The MCP server remains the sole telemetry source. This adapter exposes a tiny
authenticated HTTP surface to the Android app on the same device.
"""
from __future__ import annotations

import asyncio
import json
import os
import secrets
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

HOST = "127.0.0.1"
PORT = int(os.environ.get("EVOLUTE_MCP_BRIDGE_PORT", "8765"))
TOKEN_FILE = Path(os.environ.get(
    "EVOLUTE_MCP_BRIDGE_TOKEN_FILE",
    str(Path.home() / ".config/evolute/bridge-token")
))
MCP_COMMAND = os.environ.get("EVOLUTE_MCP_COMMAND", "evaconnect-mcp")


def load_or_create_token() -> str:
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) >= 32:
            return token
    except FileNotFoundError:
        pass
    token = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    try:
        TOKEN_FILE.chmod(0o600)
    except OSError:
        pass
    return token


async def call_mcp_tool(name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
    params = StdioServerParameters(
        command=MCP_COMMAND,
        args=[],
        env=os.environ.copy(),
    )
    async with stdio_client(params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            result = await session.call_tool(name, arguments or {})
            if getattr(result, "isError", False):
                raise RuntimeError("MCP tool returned an error")
            texts = [item.text for item in result.content if getattr(item, "type", None) == "text"]
            if not texts:
                return {"content": []}
            parsed = []
            for item in texts:
                try:
                    parsed.append(json.loads(item))
                except json.JSONDecodeError:
                    parsed.append(item)
            return parsed[0] if len(parsed) == 1 else {"content": parsed}


def run_tool(name: str) -> dict[str, Any]:
    return asyncio.run(call_mcp_tool(name))


class Handler(BaseHTTPRequestHandler):
    server_version = "EvoluteMCPBridge/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        # Never log headers, tokens, credentials, or telemetry payloads.
        print("bridge:", fmt % args)

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"ok": True, "transport": "stdio"})
            return
        if self.path != "/v1/status":
            self.send_json(404, {"error": "not_found"})
            return
        if not secrets.compare_digest(self.headers.get("X-Bridge-Token", ""), load_or_create_token()):
            self.send_json(401, {"error": "unauthorized"})
            return
        try:
            self.send_json(200, run_tool("evolute_status"))
        except Exception as exc:
            self.send_json(502, {"error": "mcp_unavailable", "detail": str(exc)[:160]})

    def do_POST(self) -> None:
        # This bridge intentionally exposes no vehicle-control or write tools.
        self.send_json(405, {"error": "method_not_allowed"})

    def do_OPTIONS(self) -> None:
        self.send_json(405, {"error": "method_not_allowed"})


def main() -> None:
    token = load_or_create_token()
    print(f"Evolute MCP bridge listening on http://{HOST}:{PORT}")
    print(f"Bridge token stored in {TOKEN_FILE} (not displayed).")
    print("Telemetry endpoint: GET /v1/status; health endpoint: GET /health")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
