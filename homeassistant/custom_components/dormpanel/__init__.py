"""DormPanel native controls and file relay."""

from hashlib import sha256
from pathlib import Path

from homeassistant.components import panel_custom, websocket_api
from homeassistant.components.frontend import async_panel_exists, async_remove_panel
from homeassistant.components.http import StaticPathConfig
from homeassistant.const import Platform
from homeassistant.helpers import device_registry as dr

from .const import DOMAIN
from .http import UploadView, DownloadView
from .relay import Relay
from .ws import COMMANDS

PLATFORMS = (Platform.NUMBER, Platform.SWITCH, Platform.SELECT, Platform.SENSOR, Platform.BINARY_SENSOR)


async def async_setup(hass, config):
    return True


async def async_setup_entry(hass, entry):
    frontend_dir = Path(__file__).parent / "frontend"
    relay = hass.data.get(DOMAIN)
    if relay is None:
        relay = Relay(hass)
        await relay.start()
        await hass.http.async_register_static_paths([
            StaticPathConfig("/dormpanel_static", str(frontend_dir), False)
        ])
        hass.data[DOMAIN] = relay
        hass.http.register_view(UploadView(relay))
        hass.http.register_view(DownloadView(relay))
        for command in COMMANDS:
            websocket_api.async_register_command(hass, command)
    else:
        relay.active = True
        await relay.cleanup()
    entry.runtime_data = relay
    registry = dr.async_get(hass)

    def update_device(installation_id):
        screen = relay.screens.get(installation_id)
        identifiers = {(DOMAIN, installation_id)}
        if screen is None:
            for device in dr.async_entries_for_config_entry(registry, entry.entry_id):
                if device.identifiers & identifiers:
                    registry.async_remove_device(device.id)
                    break
        elif relay.controls.supports(installation_id):
            registry.async_get_or_create(
                config_entry_id=entry.entry_id, identifiers=identifiers,
                name=screen["display_name"], manufacturer="DormPanel", model="X08E",
                sw_version=screen["app_version"],
            )

    entry.async_on_unload(relay.controls.listen_registration(update_device))
    for installation_id in relay.screens:
        update_device(installation_id)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    if not async_panel_exists(hass, DOMAIN):
        panel_source = await hass.async_add_executor_job((frontend_dir / "panel.js").read_bytes)
        panel_version = sha256(panel_source).hexdigest()[:12]
        await panel_custom.async_register_panel(
            hass=hass, frontend_url_path=DOMAIN,
            webcomponent_name="dormpanel-panel", module_url=f"/dormpanel_static/panel.js?v={panel_version}",
            sidebar_title="DormPanel", sidebar_icon="mdi:calendar-arrow-right",
            require_admin=True, embed_iframe=False,
        )
    relay.changed()
    return True


async def async_unload_entry(hass, entry):
    relay = entry.runtime_data
    relay.pause()
    if not await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        relay.active = True
        await relay.cleanup()
        return False
    async_remove_panel(hass, DOMAIN)
    return True


async def async_remove_entry(hass, entry):
    relay = hass.data.get(DOMAIN)
    if relay is not None:
        for installation_id in tuple(relay.controls.sessions):
            relay.controls.close(installation_id)


async def async_remove_config_entry_device(hass, entry, device):
    for domain, installation_id in device.identifiers:
        if domain == DOMAIN and installation_id in entry.runtime_data.screens:
            await entry.runtime_data.remove_screen(installation_id)
    return True
