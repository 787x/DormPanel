"""DormPanel switch settings."""

from homeassistant.components.switch import SwitchEntity

from .control import SWITCHES
from .entity import DormPanelEntity, setup_entities


async def async_setup_entry(hass, entry, async_add_entities):
    setup_entities(hass, entry, async_add_entities, lambda controls, installation_id: [
        DormPanelSwitch(controls, installation_id, field, name) for field, name in SWITCHES.items()
    ])


class DormPanelSwitch(DormPanelEntity, SwitchEntity):
    @property
    def is_on(self):
        return self.value

    async def async_turn_on(self, **kwargs):
        await self.set_value(True)

    async def async_turn_off(self, **kwargs):
        await self.set_value(False)
