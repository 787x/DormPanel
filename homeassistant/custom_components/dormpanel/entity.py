"""Shared Home Assistant entity behavior and registration for DormPanel screens."""

from homeassistant.const import EntityCategory
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity import DeviceInfo, Entity

from .const import DOMAIN
from .control import ControlError


def setup_entities(hass, entry, add_entities, factory):
    controls = entry.runtime_data.controls
    known = set()

    def update(installation_id):
        if installation_id not in controls.relay.screens:
            known.discard(installation_id)
            return
        if not controls.supports(installation_id):
            return
        if installation_id not in known:
            known.add(installation_id)
            add_entities(factory(controls, installation_id))

    # Reconcile device registration only when registration metadata changes.
    entry.async_on_unload(controls.listen_registration(update))
    for installation_id in controls.relay.screens:
        update(installation_id)


class DormPanelEntity(Entity):
    _attr_should_poll = False
    _attr_has_entity_name = True

    def __init__(self, controls, installation_id, field, name):
        self.controls = controls
        self.installation_id = installation_id
        self.field = field
        self._attr_name = name
        self._attr_unique_id = f"{installation_id}_{field}"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, installation_id)})

    @property
    def value(self):
        session = self.controls.sessions.get(self.installation_id)
        return session.values.get(self.field) if session and session.values is not None else None

    @property
    def available(self):
        return self.controls.reason(self.installation_id, self.field) is None

    async def async_added_to_hass(self):
        def update(installation_id):
            if self.installation_id == installation_id:
                self.async_write_ha_state()

        self.async_on_remove(self.controls.listen(update))

    async def set_value(self, value):
        try:
            await self.controls.execute(self.installation_id, self.field, value)
        except ControlError as error:
            raise HomeAssistantError(str(error)) from error


class DormPanelDiagnostic(DormPanelEntity):
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def available(self):
        return self.controls.supports(self.installation_id)

    @property
    def extra_state_attributes(self):
        state = self.controls.public_state(self.installation_id)
        return {"control_protocol": state["version"], "reported_controls": list(state["values"]),
                "mute_available": state["mute_available"], "control_reasons": state["reasons"]}
