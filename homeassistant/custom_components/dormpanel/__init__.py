"""DormPanel timetable relay integration."""

from pathlib import Path

from homeassistant.components import panel_custom, websocket_api
from homeassistant.components.frontend import async_panel_exists
from homeassistant.components.http import StaticPathConfig

from .const import DOMAIN
from .http import UploadView, DownloadView
from .relay import Relay
from .ws import COMMANDS


async def async_setup(hass, config):
    return True


async def async_setup_entry(hass, entry):
    relay = Relay(hass)
    await relay.start()
    hass.data[DOMAIN] = relay
    hass.http.register_view(UploadView(relay))
    hass.http.register_view(DownloadView(relay))
    for command in COMMANDS:
        websocket_api.async_register_command(hass, command)
    if not async_panel_exists(hass, DOMAIN):
        await hass.http.async_register_static_paths([
            StaticPathConfig("/dormpanel_static", str(Path(__file__).parent / "frontend"), False)
        ])
        await panel_custom.async_register_panel(
            hass=hass, frontend_url_path=DOMAIN,
            webcomponent_name="dormpanel-panel", module_url="/dormpanel_static/panel.js",
            sidebar_title="DormPanel", sidebar_icon="mdi:calendar-arrow-right",
            require_admin=True, embed_iframe=False,
        )
    return True


async def async_unload_entry(hass, entry):
    # HA does not unregister integration WebSocket commands/views on config-entry
    # unload. Keep the singleton alive until restart; adding twice is prevented by
    # the config flow unique ID.
    return False
