"""Small stdlib model adapter for the vendored evaconnect client on Android.

The upstream client logic is retained. Pydantic is replaced here because its
Rust pydantic-core dependency has no generally available Chaquopy Android wheel.
Only fields consumed by the client are normalized; unknown API fields are kept.
"""
from __future__ import annotations
from typing import Any

class Model:
    aliases: dict[str, str] = {}
    defaults: dict[str, Any] = {}
    def __init__(self, **data: Any):
        for key, value in self.defaults.items():
            setattr(self, key, value)
        for key, value in data.items():
            setattr(self, self.aliases.get(key, key), value)
    @classmethod
    def model_validate(cls, data: Any):
        if isinstance(data, cls):
            return data
        if not isinstance(data, dict):
            data = {}
        return cls(**data)

class AuthTokens(Model):
    aliases = {"userId":"user_id","accessToken":"access_token","refreshToken":"refresh_token","userToken":"user_token","widgetId":"widget_id"}
    defaults = {"user_id":None,"access_token":None,"refresh_token":None,"user_token":None,"widget_id":None}
    def __repr__(self):
        return f"AuthTokens(user_id={self.user_id!r}, has_access={bool(self.access_token)}, has_refresh={bool(self.refresh_token)})"

class ServiceInfo(Model):
    aliases = {"enableChat":"enable_chat","yandexApiKey":"yandex_api_key"}
    defaults = {"capcha":None,"enable_chat":None,"yandex_api_key":None,"ios":None,"android":None}

class User(Model):
    aliases = {"_id":"id","userGroupsIds":"user_groups_ids","pushTokens":"push_tokens"}
    defaults = {"id":None,"phone":None,"user_groups_ids":None,"push_tokens":None}

class Vehicle(Model):
    aliases = {"_id":"id","licensePlate":"plate","carModel":"car_model"}
    defaults = {"vin":None,"imei":None,"plate":None,"brand":None,"car_model":None,"options":None}
    @property
    def model(self):
        value = self.car_model
        if isinstance(value, str):
            return value
        if isinstance(value, dict):
            return value.get("name") or value.get("title") or value.get("model") or value.get("modification")
        return None
    def __repr__(self):
        return f"Vehicle(id={getattr(self,'id',None)!r}, plate={self.plate!r}, brand={self.brand!r}, model={self.model!r})"

class VehiclePage(Model):
    defaults = {"rows":[],"total":None,"offset":None,"limit":None}
    @classmethod
    def model_validate(cls, data):
        obj=super().model_validate(data)
        obj.rows=[Vehicle.model_validate(x) for x in (obj.rows or [])]
        return obj

class FeatureFlags(Model):
    defaults = {"flags":{}}

class Telemetry(Model):
    aliases = {"isOnline":"is_online","onlineState":"online_state","isCarStateReady":"is_car_state_ready","carStateUpdatedAt":"car_state_updated_at"}
    defaults = {"sensors_raw":{},"sensors":{},"buttons":[],"is_online":None,"online_state":None,"is_car_state_ready":None,"car_state_updated_at":None,"warnings":None}
    @classmethod
    def model_validate(cls, data):
        obj=super().model_validate(data)
        raw=getattr(obj,"sensors_raw",None)
        incoming=getattr(obj,"sensors",None)
        if not isinstance(raw,dict) or not raw:
            raw=incoming if isinstance(incoming,dict) else {}
        obj.sensors_raw=dict(raw)
        obj.sensors={k:coerce_sensor_value(v) for k,v in (incoming if isinstance(incoming,dict) else raw).items()}
        return obj

class ChargeSession(Model):
    pass
class TripFilter(Model):
    defaults={"key":None,"type":None,"options":[],"name":None}
class FirstLast(Model):
    defaults={"first":None,"last":None}
class TripSummary(Model):
    aliases={"segmentStartTime":"segment_start_time","segmentEndTime":"segment_end_time","startDate":"start_date","endDate":"end_date","batteryConsumption":"battery_consumption"}
    defaults={"segment_start_time":None,"segment_end_time":None,"start_date":None,"end_date":None,"title":None,"description":None,"duration":None,"distance":None,"battery_consumption":None,"odometer":None,"battery":None,"fuel":None}
class TripDetails(TripSummary):
    aliases={**TripSummary.aliases,"startAddr":"start_addr","endAddr":"end_addr","pointsTotal":"points_total"}
    defaults={**TripSummary.defaults,"start_addr":None,"end_addr":None,"points_total":None,"points":[]}
class TripPage(Model):
    defaults={"rows":[],"total":None,"offset":None,"limit":None,"filters":None}
    @classmethod
    def model_validate(cls,data):
        obj=super().model_validate(data)
        obj.rows=[TripSummary.model_validate(x) for x in (obj.rows or [])]
        return obj

def coerce_sensor_value(value: Any) -> Any:
    if isinstance(value,bool) or value is None or not isinstance(value,str):
        return value
    text=value.strip()
    if not text:
        return value
    if text.lower()=="true": return True
    if text.lower()=="false": return False
    try:
        return float(text) if any(c in text for c in ".eE") else int(text)
    except ValueError:
        return value
