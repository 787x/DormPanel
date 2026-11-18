"""DormPanel numeric settings."""

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.const import PERCENTAGE

from .control import NUMBERS
from .entity import DormPanelEntity, setup_entities


async def async_setup_entry(hass, entry, async_add_entities):
    setup_entities(hass, entry, async_add_entities, lambda controls, installation_id: [
        DormPanelNumber(controls, installation_id, field, name)
        for field, (name, _) in NUMBERS.items()
    ])


class DormPanelNumber(DormPanelEntity, NumberEntity):
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_mode = NumberMode.SLIDER

    @property
    def native_min_value(self):
        if self.field == "media_volume" and not self.controls.public_state(self.installation_id)["mute_available"]:
            return 1
        return NUMBERS[self.field][1]

    @property
    def native_value(self):
        return self.value

    async def async_set_native_value(self, value):
        # HA represents number service values as floats. Our wire values are integers.
        await self.set_value(int(value) if float(value).is_integer() else value)
