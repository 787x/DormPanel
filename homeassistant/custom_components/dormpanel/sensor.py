"""DormPanel app version and control capabilities."""

from homeassistant.components.sensor import SensorEntity

from .entity import DormPanelDiagnostic, setup_entities


async def async_setup_entry(hass, entry, async_add_entities):
    setup_entities(hass, entry, async_add_entities, lambda controls, installation_id: [
        DormPanelVersion(controls, installation_id, "app_version", "App version"),
    ])


class DormPanelVersion(DormPanelDiagnostic, SensorEntity):
    @property
    def native_value(self):
        return self.controls.relay.screens.get(self.installation_id, {}).get("app_version")
