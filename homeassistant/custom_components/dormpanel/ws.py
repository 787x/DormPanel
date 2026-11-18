"""WebSocket commands on the existing Home Assistant connection."""

from datetime import timedelta
from functools import wraps
from inspect import iscoroutinefunction
from importlib import import_module

from homeassistant.components import websocket_api
from homeassistant.components.http.auth import async_sign_path
from homeassistant.components.websocket_api.messages import BASE_COMMAND_MESSAGE_SCHEMA

from .const import DOMAIN, TERMINAL
from .control import ControlError, VERSION

# Match the validator used by the running HA WebSocket API (voluptuous on
# older Core, probatio on newer Core). Their schema markers cannot be mixed.
vol = import_module(type(BASE_COMMAND_MESSAGE_SCHEMA).__module__.partition(".")[0])


def relay(hass):
    return hass.data[DOMAIN]


def loaded(handler):
    """Reject calls while the entry is unloaded, retaining the registered routes."""
    def check(hass, connection, msg):
        if relay(hass).active:
            return True
        connection.send_error(msg["id"], "not_loaded", "DormPanel integration is not loaded")
        return False

    if iscoroutinefunction(handler):
        @wraps(handler)
        async def async_handler(hass, connection, msg):
            if check(hass, connection, msg):
                await handler(hass, connection, msg)
        return async_handler

    @wraps(handler)
    def sync_handler(hass, connection, msg):
        if check(hass, connection, msg):
            handler(hass, connection, msg)
    return sync_handler


def fail(connection, msg, error):
    connection.send_error(msg["id"], "dormpanel_error", str(error))


IDENTITY = {
    vol.Required("installation_id"): vol.Match(r"^[0-9a-f-]{36}$"),
    vol.Required("device_secret"): vol.Match(r"^[A-Za-z0-9_-]{43,128}$"),
}


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/register", **IDENTITY,
    vol.Required("display_name"): vol.All(str, vol.Length(min=1, max=80)),
    vol.Required("app_version"): vol.All(str, vol.Length(max=40)),
    vol.Required("capabilities"): vol.All([vol.All(str, vol.Length(max=40))], vol.Length(max=20))})
@websocket_api.async_response
@loaded
async def register(hass, connection, msg):
    try:
        await relay(hass).register(msg["installation_id"], msg["device_secret"], msg["display_name"],
                                   msg["app_version"], msg["capabilities"], connection.user.is_admin)
        connection.send_result(msg["id"], {})
    except (ValueError, PermissionError) as error:
        fail(connection, msg, error)


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/list_pending", **IDENTITY})
@loaded
def list_pending(hass, connection, msg):
    try:
        connection.send_result(msg["id"], relay(hass).pending(msg["installation_id"], msg["device_secret"]))
    except (PermissionError, ValueError) as error:
        fail(connection, msg, error)


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/claim_transfer", **IDENTITY,
    vol.Required("transfer_id"): vol.Match(r"^[0-9a-f]{32}$")})
@loaded
def claim_transfer(hass, connection, msg):
    try:
        item, claim_id = relay(hass).claim(msg["installation_id"], msg["device_secret"], msg["transfer_id"])
        path = f"/api/dormpanel/transfers/{item['transfer_id']}/{claim_id}"
        connection.send_result(msg["id"], {"transfer_id": item["transfer_id"], "filename": item["filename"],
            "size": item["size"], "sha256": item["sha256"], "kind": item.get("kind", "schedule_ics"),
            "signed_path": async_sign_path(hass, path, timedelta(seconds=60)), "expires_in": 60})
    except (ValueError, PermissionError) as error:
        fail(connection, msg, error)


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/ack_transfer", **IDENTITY,
    vol.Required("transfer_id"): vol.Match(r"^[0-9a-f]{32}$"),
    vol.Required("outcome"): vol.In(("preview_ready", *TERMINAL))})
@websocket_api.async_response
@loaded
async def ack_transfer(hass, connection, msg):
    try:
        await relay(hass).acknowledge(msg["installation_id"], msg["device_secret"],
                                      msg["transfer_id"], msg["outcome"])
        connection.send_result(msg["id"], {})
    except (PermissionError, ValueError) as error:
        fail(connection, msg, error)


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "dormpanel/admin_state"})
@loaded
def admin_state(hass, connection, msg):
    connection.send_result(msg["id"], relay(hass).admin_state())


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "dormpanel/remove_screen",
    vol.Required("installation_id"): vol.Match(r"^[0-9a-f-]{36}$")})
@websocket_api.async_response
@loaded
async def remove_screen(hass, connection, msg):
    try:
        await relay(hass).remove_screen(msg["installation_id"])
        connection.send_result(msg["id"], {})
    except ValueError as error:
        fail(connection, msg, error)


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "dormpanel/cancel_transfer",
    vol.Required("transfer_id"): vol.Match(r"^[0-9a-f]{32}$")})
@websocket_api.async_response
@loaded
async def cancel_transfer(hass, connection, msg):
    try:
        await relay(hass).cancel(msg["transfer_id"])
        connection.send_result(msg["id"], {})
    except ValueError as error:
        fail(connection, msg, error)


INSTALLATION = vol.Match(r"^[0-9a-f-]{36}$")
CONTROL_ID = vol.Match(r"^[0-9a-f]{32}$")


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/control_protocol"})
def control_protocol(hass, connection, msg):
    connection.send_result(msg["id"], {"version": VERSION})


@websocket_api.websocket_command({
    vol.Required("type"): "dormpanel/control_subscribe", **IDENTITY,
    vol.Required("version"): vol.In((VERSION,)),
})
@loaded
def control_subscribe(hass, connection, msg):
    controls = relay(hass).controls
    installation_id = msg["installation_id"]
    try:
        subscription_id = msg["id"]
        session = controls.open(
            installation_id, msg["device_secret"], connection,
            lambda event: connection.send_event(subscription_id, event),
            lambda: connection.subscriptions.pop(subscription_id, None),
        )
    except (ValueError, PermissionError) as error:
        fail(connection, msg, error)
        return
    connection.subscriptions[subscription_id] = lambda: controls.close(installation_id, session, unlink=False)
    connection.send_result(subscription_id, {"version": VERSION, "session": session.token})


@websocket_api.websocket_command({
    vol.Required("type"): "dormpanel/control_admit",
    vol.Required("installation_id"): INSTALLATION,
    vol.Required("session"): CONTROL_ID,
    vol.Required("command_id"): CONTROL_ID,
})
@loaded
def control_admit(hass, connection, msg):
    try:
        remaining = relay(hass).controls.admit(msg["installation_id"], msg["session"], connection, msg["command_id"])
    except ControlError as error:
        connection.send_error(msg["id"], error.code, str(error))
    else:
        connection.send_result(msg["id"], {"remaining_ms": remaining})


@websocket_api.websocket_command({
    vol.Required("type"): "dormpanel/control_report",
    vol.Required("installation_id"): INSTALLATION,
    vol.Required("session"): CONTROL_ID,
    vol.Required("values"): dict,
    vol.Required("system_write"): bool,
    vol.Required("mute_available"): bool,
    vol.Optional("command_id"): CONTROL_ID,
    vol.Optional("error"): vol.All(str, vol.Length(max=32)),
})
def control_report(hass, connection, msg):
    try:
        relay(hass).controls.report(msg["installation_id"], msg["session"], connection,
                                    msg["values"], msg["system_write"], msg["mute_available"],
                                    msg.get("command_id"), msg.get("error"))
    except ControlError as error:
        connection.send_error(msg["id"], error.code, str(error))
    else:
        connection.send_result(msg["id"])


@websocket_api.require_admin
@websocket_api.websocket_command({
    vol.Required("type"): "dormpanel/control_set",
    vol.Required("installation_id"): INSTALLATION,
    vol.Required("field"): vol.All(str, vol.Length(max=32)),
    vol.Required("value"): vol.Any(int, bool, str),
})
@websocket_api.async_response
@loaded
async def control_set(hass, connection, msg):
    try:
        actual = await relay(hass).controls.execute(msg["installation_id"], msg["field"], msg["value"])
    except ControlError as error:
        connection.send_error(msg["id"], error.code, str(error))
    else:
        connection.send_result(msg["id"], {"value": actual})


COMMANDS = (register, list_pending, claim_transfer, ack_transfer, admin_state, remove_screen, cancel_transfer,
            control_protocol, control_subscribe, control_admit, control_report, control_set)
