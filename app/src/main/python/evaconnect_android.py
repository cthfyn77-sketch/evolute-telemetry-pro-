"""Android bridge: run the vendored evaconnect client and return redacted telemetry."""
from __future__ import annotations
import json
from evaconnect.auth import TokenStore
from evaconnect.client import EvoluteClient
import re


_SENSITIVE_KEY = re.compile(
    r"(access.?token|refresh.?token|authorization|password|secret|imei|vin|phone|"
    r"user.?id|car.?id|vehicle.?id|(^|_)id$)",
    re.IGNORECASE,
)


def _redact_api_value(value):
    """Keep telemetry fields, but never persist account/vehicle credentials or IDs."""
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if _SENSITIVE_KEY.search(str(key)):
                result[str(key)] = "[REDACTED]"
            else:
                result[str(key)] = _redact_api_value(item)
        return result
    if isinstance(value, (list, tuple)):
        return [_redact_api_value(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)



def _error_payload(exc):
    message = str(exc) or type(exc).__name__
    message = re.sub(r"/car-service/car/v2/[0-9a-fA-F]{24}", "/car-service/car/v2/{carId}", message)
    message = re.sub(r"/client-bff-service/telemetry/[^\s]+", "/client-bff-service/telemetry/{vehicle}", message)
    message = re.sub(r"(access-token|refresh-token|authorization)\s*[:=]\s*[^,;\s]+", r"\1=[REDACTED]", message, flags=re.IGNORECASE)
    message = re.sub(r"[\r\n\t]+", " ", message).strip()
    return {"ok": False, "error": (message or type(exc).__name__)[:180]}


def request_otp(phone: str, phone_country: str) -> str:
    try:
        with EvoluteClient(token_store=TokenStore(path=None)) as client:
            info = client.get_info()
            if getattr(info, "capcha", False):
                raise ValueError("EVACONNECT требует CAPTCHA; автоматический обход не выполняется")
            client.request_otp(phone.strip(), phone_country.strip().upper())
        return json.dumps({"ok": True}, ensure_ascii=False, separators=(",", ":"))
    except Exception as exc:
        return json.dumps(_error_payload(exc), ensure_ascii=False, separators=(",", ":"))


def sign_in(phone: str, code: str) -> str:
    store = TokenStore(path=None)
    try:
        with EvoluteClient(token_store=store) as client:
            tokens = client.sign_in(phone.strip(), code.strip())
        return json.dumps({
            "ok": True,
            "access_token": store.access_token or getattr(tokens, "access_token", ""),
            "refresh_token": store.refresh_token or getattr(tokens, "refresh_token", ""),
            "user_id": store.user_id or getattr(tokens, "user_id", None),
            "user_token": store.user_token or getattr(tokens, "user_token", None),
            "widget_id": store.widget_id or getattr(tokens, "widget_id", None),
        }, ensure_ascii=False, separators=(",", ":"))
    except Exception as exc:
        result = _error_payload(exc)
        result["access_token"] = store.access_token or ""
        result["refresh_token"] = store.refresh_token or ""
        return json.dumps(result, ensure_ascii=False, separators=(",", ":"))


def fetch_telemetry(access_token: str, refresh_token: str) -> str:
    store = TokenStore(path=None)
    store.access_token = access_token or None
    store.refresh_token = refresh_token or None
    try:
        with EvoluteClient(token_store=store) as client:
            car_id = client.default_car_id()
            telemetry = client.get_telemetry(car_id=car_id)
            sensors = dict(getattr(telemetry, "sensors", None) or {})
            soc = sensors.get("batteryPercentage")
            odometer = sensors.get("odometer")
            stock_range = sensors.get("remainsBatteryMileage")
            if soc is None or odometer is None:
                raise ValueError("evaconnect вернул телеметрию без SOC или одометра")
            payload = {
                "ok": True,
                "soc": float(soc),
                "odometer": float(odometer),
                "stock_range": None if stock_range in (None, "") else float(stock_range),
                "online": bool(getattr(telemetry, "is_online", False)),
                "ready": bool(getattr(telemetry, "is_car_state_ready", False)),
                "updated_at": str(getattr(telemetry, "car_state_updated_at", "") or ""),
                # Preserve the complete decoded telemetry response, including
                # top-level fields and every sensor. Redact account/vehicle identifiers.
                "sensors_json": json.dumps(
                    _redact_api_value(
                        getattr(telemetry, "api_raw", None)
                        or {"sensors": getattr(telemetry, "sensors_raw", None) or sensors}
                    ),
                    ensure_ascii=False,
                    separators=(",", ":"),
                    default=str,
                ),
            }
    except Exception as exc:
        # Keep enough context to identify which evaconnect request was rejected,
        # without ever exposing credentials or vehicle/account identifiers.
        status = getattr(exc, "status_code", None)
        cause = getattr(exc, "__cause__", None)
        if status is None and cause is not None:
            status = getattr(cause, "status_code", None)

        message = str(exc) if not isinstance(exc, ValueError) else str(exc)
        message = re.sub(r"/car-service/car/v2/[0-9a-fA-F]{24}", "/car-service/car/v2/{carId}", message)
        message = re.sub(r"/client-bff-service/telemetry/[^\s]+", "/client-bff-service/telemetry/{vehicle}", message)
        message = re.sub(r"(access-token|refresh-token|authorization)\s*[:=]\s*[^,;\s]+", r"\1=[REDACTED]", message, flags=re.IGNORECASE)
        message = re.sub(r"[\r\n\t]+", " ", message).strip()
        if not message:
            message = type(exc).__name__
        if status is not None and f"HTTP {status}" not in message:
            message = f"{message} (HTTP {status})"
        error = message[:180]
        payload = {"ok": False, "error": error}
    payload["access_token"] = store.access_token or ""
    payload["refresh_token"] = store.refresh_token or ""
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
