"""WebSocket commands on the existing Home Assistant connection."""

from datetime import timedelta
import re

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.http.auth import async_sign_path

from .const import DOMAIN, TERMINAL


def relay(hass):
    return hass.data[DOMAIN]


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
async def register(hass, connection, msg):
    try:
        await relay(hass).register(msg["installation_id"], msg["device_secret"], msg["display_name"],
                                   msg["app_version"], msg["capabilities"], connection.user.is_admin)
        connection.send_result(msg["id"], {})
    except (ValueError, PermissionError) as error:
        fail(connection, msg, error)


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/list_pending", **IDENTITY})
def list_pending(hass, connection, msg):
    try:
        connection.send_result(msg["id"], relay(hass).pending(msg["installation_id"], msg["device_secret"]))
    except (PermissionError, ValueError) as error:
        fail(connection, msg, error)


@websocket_api.websocket_command({vol.Required("type"): "dormpanel/claim_transfer", **IDENTITY,
    vol.Required("transfer_id"): vol.Match(r"^[0-9a-f]{32}$")})
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
async def ack_transfer(hass, connection, msg):
    try:
        await relay(hass).acknowledge(msg["installation_id"], msg["device_secret"],
                                      msg["transfer_id"], msg["outcome"])
        connection.send_result(msg["id"], {})
    except (PermissionError, ValueError) as error:
        fail(connection, msg, error)


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "dormpanel/admin_state"})
def admin_state(hass, connection, msg):
    connection.send_result(msg["id"], relay(hass).admin_state())


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "dormpanel/remove_screen",
    vol.Required("installation_id"): vol.Match(r"^[0-9a-f-]{36}$")})
@websocket_api.async_response
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
async def cancel_transfer(hass, connection, msg):
    try:
        await relay(hass).cancel(msg["transfer_id"])
        connection.send_result(msg["id"], {})
    except ValueError as error:
        fail(connection, msg, error)


COMMANDS = (register, list_pending, claim_transfer, ack_transfer, admin_state, remove_screen, cancel_transfer)
