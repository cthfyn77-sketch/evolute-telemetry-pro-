#!/usr/bin/env python3
"""Safe smoke test for the local EVACONNECT MCP bridge.

Reads the bridge token from the same private file as the bridge. Never prints
credentials, raw telemetry, GPS coordinates, or the complete response.
"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE_URL = "http://127.0.0.1:8765"
TOKEN_FILE = Path(os.environ.get(
    "EVOLUTE_MCP_BRIDGE_TOKEN_FILE",
    str(Path.home() / ".config/evolute/bridge-token"),
))


def get_json(path: str, token: str | None = None) -> tuple[int, dict]:
    headers = {"Accept": "application/json", "Cache-Control": "no-store"}
    if token is not None:
        headers["X-Bridge-Token"] = token
    request = Request(BASE_URL + path, headers=headers, method="GET")
    try:
        with urlopen(request, timeout=25) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = {}
        return exc.code, payload
    except (URLError, TimeoutError, OSError) as exc:
        raise RuntimeError(f"Не удалось подключиться к локальному bridge: {type(exc).__name__}") from None


def valid_number(value: object, minimum: float, maximum: float | None = None) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    number = float(value)
    return math.isfinite(number) and number >= minimum and (maximum is None or number <= maximum)


def main() -> int:
    try:
        health_code, health = get_json("/health")
        if health_code != 200 or health.get("ok") is not True:
            raise RuntimeError(f"/health вернул HTTP {health_code}")
        print("OK: локальный HTTP bridge доступен")

        if not TOKEN_FILE.is_file():
            raise RuntimeError("Файл bridge-token не найден; сначала запустите bridge")
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) < 32:
            raise RuntimeError("Файл bridge-token содержит некорректный ключ")

        status_code, status = get_json("/v1/status", token)
        if status_code == 401:
            raise RuntimeError("Bridge отклонил ключ; проверьте файл bridge-token")
        if status_code != 200:
            raise RuntimeError(f"MCP status недоступен: HTTP {status_code}")
        if not isinstance(status, dict):
            raise RuntimeError("MCP вернул неожиданный формат JSON")

        charge = status.get("charge")
        drive = status.get("drive")
        if not isinstance(charge, dict) or not isinstance(drive, dict):
            raise RuntimeError("В ответе MCP отсутствует раздел charge или drive")
        soc = charge.get("battery_percentage")
        odo = drive.get("odometer")
        if not valid_number(soc, 0, 100):
            raise RuntimeError("MCP не вернул корректный SOC (0–100)")
        if not valid_number(odo, 0):
            raise RuntimeError("MCP не вернул корректный одометр")

        online = status.get("online")
        print("OK: авторизация bridge принята")
        print(f"OK: MCP вернул структуру телеметрии; online={online if isinstance(online, bool) else 'unknown'}")
        print(f"OK: SOC={float(soc):.1f}%")
        print(f"OK: одометр={float(odo):.1f} км")
        print("Проверка завершена. Секреты и GPS-данные не выводились.")
        return 0
    except (RuntimeError, json.JSONDecodeError, UnicodeError) as exc:
        print(f"ОШИБКА: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
