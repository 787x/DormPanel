"""Versioned, connection-bound native device control. All methods run on the HA loop."""

import asyncio
from dataclasses import dataclass
import secrets
import time

CAPABILITY = "device_control_v1"
VERSION = 1
COMMAND_TIMEOUT = 5

NUMBERS = {
    "display_brightness": ("DormPanel brightness", 1),
    "system_brightness": ("System brightness", 1),
    "media_volume": ("Media volume", 0),
    "card_opacity": ("Card background opacity", 0),
}
SWITCHES = {
    "blackout": "Blackout",
    "system_automatic": "Automatic system brightness",
    "follow_system": "Follow system brightness",
    "keep_awake": "Keep screen awake",
    "start_after_boot": "Start after boot",
}
THEMES = ("light", "dark")
ERRORS = {
    "offline": "DormPanel device is offline or has not reported state",
    "unsupported": "Control is unsupported by this device",
    "permission": "Android system settings permission is required",
    "automatic": "System automatic brightness is enabled",
    "following": "DormPanel is following system brightness",
    "invalid_value": "Invalid device control value",
    "busy": "A DormPanel command is already in progress",
    "timeout": "The device did not confirm the command in time",
    "execution_failed": "The device could not apply the command",
}
DEVICE_ERRORS = {"permission", "unsupported", "automatic", "following", "invalid_value", "timeout", "execution_failed"}

def clock_ms():
    return time.monotonic_ns() // 1_000_000


class ControlError(ValueError):
    def __init__(self, code):
        super().__init__(ERRORS[code])
        self.code = code


def valid_value(field, value):
    if field in NUMBERS:
        return type(value) is int and NUMBERS[field][1] <= value <= 100
    if field in SWITCHES:
        return type(value) is bool
    return field == "theme" and value in THEMES


def validate_snapshot(values, system_write, mute_available):
    if (not isinstance(values, dict) or any(not valid_value(field, value) for field, value in values.items())
            or type(system_write) is not bool or type(mute_available) is not bool):
        raise ControlError("invalid_value")


@dataclass
class Session:
    connection: object
    send: object
    token: str
    unlink: object
    values: dict | None = None
    system_write: bool = False
    mute_available: bool = False
    pending: "PendingCommand | None" = None


@dataclass
class PendingCommand:
    command_id: str
    result: asyncio.Future
    field: str
    deadline: int
    admitted: bool = False


class Controls:
    def __init__(self, relay):
        self.relay = relay
        self.sessions = {}
        self.listeners = set()
        self.registration_listeners = set()

    def listen(self, callback):
        self.listeners.add(callback)
        return lambda: self.listeners.discard(callback)

    def listen_registration(self, callback):
        self.registration_listeners.add(callback)
        return lambda: self.registration_listeners.discard(callback)

    def changed(self, installation_id):
        for listener in tuple(self.listeners):
            listener(installation_id)
        self.relay.changed()

    def registered(self, installation_id):
        if not self.supports(installation_id):
            self.close(installation_id)
        for listener in tuple(self.registration_listeners):
            listener(installation_id)
        self.changed(installation_id)

    def supports(self, installation_id):
        return CAPABILITY in self.relay.screens.get(installation_id, {}).get("capabilities", ())

    def open(self, installation_id, secret, connection, send, unlink=lambda: None):
        self.relay.authorize(installation_id, secret)
        if not self.supports(installation_id):
            raise ControlError("unsupported")
        self.close(installation_id)
        session = Session(connection, send, secrets.token_hex(16), unlink)
        self.sessions[installation_id] = session
        return session

    def close(self, installation_id, session=None, *, unlink=True):
        current = self.sessions.get(installation_id)
        if current is None or (session is not None and session is not current):
            return
        del self.sessions[installation_id]
        if unlink:
            current.unlink()
        if current.pending:
            future = current.pending.result
            if not future.done():
                future.set_exception(ControlError("offline"))
        self.changed(installation_id)

    def cancel_commands(self):
        for session in self.sessions.values():
            if session.pending and not session.pending.result.done():
                session.pending.result.set_exception(ControlError("offline"))

    def _session(self, installation_id, token, connection):
        session = self.sessions.get(installation_id)
        if (not session or session.connection is not connection or session.token != token
                or not self.supports(installation_id)):
            raise ControlError("offline")
        return session

    def admit(self, installation_id, token, connection, command_id):
        session = self._session(installation_id, token, connection)
        pending = session.pending
        if not pending or pending.command_id != command_id or pending.result.done():
            raise ControlError("timeout")
        remaining = pending.deadline - clock_ms()
        if remaining <= 0:
            raise ControlError("timeout")
        pending.admitted = True
        return remaining

    def report(self, installation_id, token, connection, values, system_write, mute_available,
               command_id=None, error=None):
        session = self._session(installation_id, token, connection)
        validate_snapshot(values, system_write, mute_available)
        if command_id is not None and (error is not None and error not in DEVICE_ERRORS):
            raise ControlError("invalid_value")
        changed = (session.values, session.system_write, session.mute_available) != (values, system_write, mute_available)
        session.values, session.system_write, session.mute_available = values.copy(), system_write, mute_available
        if changed:
            self.changed(installation_id)
        pending = session.pending
        if command_id is not None and pending and pending.command_id == command_id and pending.admitted:
            future = pending.result
            if not future.done() and clock_ms() < pending.deadline:
                if error:
                    future.set_exception(ControlError(error))
                else:
                    field = pending.field
                    if field in values:
                        future.set_result(values[field])
                    else:
                        future.set_exception(ControlError("unsupported"))

    def reason(self, installation_id, field):
        if not self.supports(installation_id):
            return "unsupported"
        session = self.sessions.get(installation_id)
        if not self.relay.active or not session or session.values is None:
            return "offline"
        if field not in session.values:
            return "unsupported"
        if field in ("system_brightness", "system_automatic") and not session.system_write:
            return "permission"
        if field == "system_brightness" and session.values.get("system_automatic"):
            return "automatic"
        if field == "display_brightness" and session.values.get("follow_system"):
            return "following"
        return None

    def public_state(self, installation_id):
        session = self.sessions.get(installation_id)
        values = session.values if session else None
        return {
            "version": VERSION if self.supports(installation_id) else None,
            "online": self.relay.active and values is not None,
            "values": values or {},
            "mute_available": bool(session and session.mute_available),
            "reasons": {field: reason for field in (*NUMBERS, *SWITCHES, "theme")
                        if (reason := self.reason(installation_id, field))},
        }

    async def execute(self, installation_id, field, value):
        if not valid_value(field, value):
            raise ControlError("invalid_value")
        if reason := self.reason(installation_id, field):
            raise ControlError(reason)
        session = self.sessions[installation_id]
        if field == "media_volume" and value == 0 and not session.mute_available:
            raise ControlError("unsupported")
        if session.pending:
            raise ControlError("busy")
        command_id = secrets.token_hex(16)
        future = asyncio.get_running_loop().create_future()
        session.pending = PendingCommand(command_id, future, field, clock_ms() + int(COMMAND_TIMEOUT * 1000))
        try:
            session.send({
                "version": VERSION, "installation_id": installation_id, "session": session.token,
                "command_id": command_id, "field": field, "value": value,
            })
            return await asyncio.wait_for(future, COMMAND_TIMEOUT)
        except TimeoutError as error:
            raise ControlError("timeout") from error
        finally:
            session.pending = None
            if not future.done():
                future.cancel()
