"""DormPanel connection status."""

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity

from .entity import DormPanelDiagnostic, setup_entities


async def async_setup_entry(hass, entry, async_add_entities):
    setup_entities(hass, entry, async_add_entities, lambda controls, installation_id: [
        DormPanelOnline(controls, installation_id, "connected", "Connection"),
    ])


class DormPanelOnline(DormPanelDiagnostic, BinarySensorEntity):
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    @property
    def is_on(self):
        return self.controls.public_state(self.installation_id)["online"]
