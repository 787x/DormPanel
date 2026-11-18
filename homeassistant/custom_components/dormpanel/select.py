"""DormPanel theme selection."""

from homeassistant.components.select import SelectEntity

from .control import THEMES
from .entity import DormPanelEntity, setup_entities


async def async_setup_entry(hass, entry, async_add_entities):
    setup_entities(hass, entry, async_add_entities, lambda controls, installation_id: [
        DormPanelTheme(controls, installation_id, "theme", "Theme"),
    ])


class DormPanelTheme(DormPanelEntity, SelectEntity):
    _attr_options = list(THEMES)

    @property
    def current_option(self):
        return self.value

    async def async_select_option(self, option):
        await self.set_value(option)
