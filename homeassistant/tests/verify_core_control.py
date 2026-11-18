"""Opt-in test using real HA Core and synthetic devices in a temporary config directory."""

import asyncio
import json
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile

import aiohttp
from homeassistant.auth.const import GROUP_ID_ADMIN, GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.bootstrap import async_setup_hass
from homeassistant.config_entries import SOURCE_USER
from homeassistant.const import __version__
from homeassistant.helpers import device_registry, entity_registry
from homeassistant.runner import RuntimeConfig

ROOT = Path(__file__).resolve().parents[1] / "custom_components" / "dormpanel"
A = "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa"
B = "bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb"
LEGACY = "eeeeeeee-eeee-4eee-eeee-eeeeeeeeeeee"
VALUES = dict(display_brightness=52, system_brightness=40, media_volume=33, card_opacity=80,
    blackout=False, system_automatic=False, follow_system=False, keep_awake=True, start_after_boot=False, theme="dark")


class Peer:
    def __init__(self, ws):
        self.ws = ws
        self.id = 0

    @classmethod
    async def open(cls, client, token, port):
        ws = await client.ws_connect(f"http://127.0.0.1:{port}/api/websocket", timeout=aiohttp.ClientWSTimeout(ws_receive=8))
        assert (await ws.receive_json())["type"] == "auth_required"
        await ws.send_json({"type": "auth", "access_token": token})
        assert (await ws.receive_json())["type"] == "auth_ok"
        return cls(ws)

    async def call(self, command, **args):
        self.id += 1
        await self.ws.send_json({"id": self.id, "type": command, **args})
        reply = await self.ws.receive_json()
        assert reply["id"] == self.id, reply
        return reply

    async def ok(self, command, **args):
        reply = await self.call(command, **args)
        assert reply["success"], reply
        return reply.get("result")


async def wait_until(predicate):
    async with asyncio.timeout(5):
        while not predicate():
            await asyncio.sleep(.04)


async def verify(scratch, port):
    hass = await async_setup_hass(RuntimeConfig(config_dir=scratch))
    assert hass is not None
    try:
        await hass.async_start()
        flow = await hass.config_entries.flow.async_init("dormpanel", context={"source": SOURCE_USER})
        result = await hass.config_entries.flow.async_configure(flow["flow_id"], {})
        entry = result["result"]
        assert entry.state.value == "loaded", entry.state
        assert len(hass.config_entries.async_entries("dormpanel")) == 1
        admin = await hass.auth.async_create_user("Fixture administrator", group_ids=[GROUP_ID_ADMIN])
        reader = await hass.auth.async_create_user("Fixture reader", group_ids=[GROUP_ID_USER])
        async def token(user):
            credential = await hass.auth.async_create_refresh_token(
                user, client_name="fixture", token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN)
            return hass.auth.async_create_access_token(credential)
        admin_token = await token(admin)
        reader_token = await token(reader)
        async with aiohttp.ClientSession() as client:
            async with client.get(f"http://127.0.0.1:{port}/dormpanel_static/panel.js?v=fixture") as response:
                assert response.status == 200
                assert "dormpanel-overview" in await response.text()
            manager = await Peer.open(client, admin_token, port)
            restricted = await Peer.open(client, reader_token, port)
            first = await Peer.open(client, admin_token, port)
            second = await Peer.open(client, admin_token, port)
            credentials = {id_: secrets.token_urlsafe(32) for id_ in (A, B, LEGACY)}

            async def register(peer, id_, name="Fixture A", native=True):
                await peer.ok("dormpanel/register", installation_id=id_, device_secret=credentials[id_],
                              display_name=name, app_version="pr90", capabilities=["device_control_v1"] if native else [])

            async def subscribe(peer, id_):
                return await peer.ok("dormpanel/control_subscribe", installation_id=id_,
                                     device_secret=credentials[id_], version=1)

            async def report(peer, id_, session, values=VALUES, **ack):
                return await peer.ok("dormpanel/control_report", installation_id=id_,
                    session=session["session"], values=values, system_write=True, mute_available=False, **ack)

            assert await first.ok("dormpanel/control_protocol") == {"version": 1}
            await register(first, A)
            await register(second, B, "Fixture B")
            await register(second, LEGACY, "Old X08E", native=False)
            one, two = await subscribe(first, A), await subscribe(second, B)
            await report(first, A, one)
            await report(second, B, two, {**VALUES, "media_volume": 66})
            devices = device_registry.async_get(hass)
            entities = entity_registry.async_get(hass)
            def find_device(id_):
                return next((item for item in device_registry.async_entries_for_config_entry(devices, entry.entry_id)
                             if ("dormpanel", id_) in item.identifiers), None)
            def registered():
                return {e.unique_id: e.entity_id for e in entities.entities.values() if e.platform == "dormpanel"}
            await wait_until(lambda: len(registered()) == 24)
            assert find_device(LEGACY) is None
            screen = find_device(A)
            assert screen.sw_version == "pr90"
            stable_ids = registered()
            def entity(field):
                return stable_ids[f"{A}_{field}"]
            await wait_until(lambda: hass.states.get(entity("media_volume")) is not None)
            assert float(hass.states.get(entity("media_volume")).state) == 33
            assert float(hass.states.get(stable_ids[f"{B}_media_volume"]).state) == 66

            for command, args in (
                ("dormpanel/admin_state", {}),
                ("dormpanel/control_set", dict(installation_id=A, field="media_volume", value=40)),
                ("dormpanel/remove_screen", dict(installation_id=A)),
            ):
                failure = await restricted.call(command, **args)
                assert not failure["success"] and failure["error"]["code"] == "unauthorized", failure
            misbound = await second.call("dormpanel/control_report", installation_id=A, session=one["session"],
                values=VALUES, system_write=True, mute_available=False)
            assert misbound["error"]["code"] == "offline"

            task = asyncio.create_task(manager.ok("call_service", domain="number", service="set_value",
                target={"entity_id": entity("media_volume")}, service_data={"value": 48}))
            event = (await first.ws.receive_json())["event"]
            assert event["installation_id"] == A and event["value"] == 48
            assert float(hass.states.get(entity("media_volume")).state) == 33
            permit = await first.ok("dormpanel/control_admit", installation_id=A,
                                    session=one["session"], command_id=event["command_id"])
            assert 0 < permit["remaining_ms"] <= 5000
            await report(first, A, one, {**VALUES, "media_volume": 47}, command_id=event["command_id"])
            await task
            assert float(hass.states.get(entity("media_volume")).state) == 47
            assert float(hass.states.get(stable_ids[f"{B}_media_volume"]).state) == 66

            await report(first, A, one, {**VALUES, "theme": "light", "system_automatic": True, "follow_system": True})
            assert hass.states.get(entity("theme")).state == "light"
            assert hass.states.is_state(entity("system_brightness"), "unavailable")
            assert hass.states.get(entity("connected")).attributes["control_reasons"]["system_brightness"] == "automatic"
            reason = await manager.call("dormpanel/control_set", installation_id=A, field="display_brightness", value=90)
            assert reason["error"]["code"] == "following"

            await register(first, A, "Renamed A")
            assert find_device(A).id == screen.id
            assert registered() == stable_ids
            assert await hass.config_entries.async_reload(entry.entry_id)
            await wait_until(lambda: hass.states.get(entity("theme")).state == "light")
            assert registered() == stable_ids
            await first.ws.close()
            await wait_until(lambda: hass.states.get(entity("connected")).state == "off")
            assert hass.states.get(entity("theme")).state == "unavailable"
            assert (await manager.call("dormpanel/control_set", installation_id=A, field="theme", value="dark"))["error"]["code"] == "offline"
            fresh = await Peer.open(client, admin_token, port)
            await register(fresh, A, "Renamed A")
            fresh_session = await subscribe(fresh, A)
            await report(fresh, A, fresh_session)
            await wait_until(lambda: hass.states.get(entity("theme")).state == "dark")
            assert registered() == stable_ids

            await manager.ok("dormpanel/remove_screen", installation_id=A)
            await wait_until(lambda: len(registered()) == 12)
            assert find_device(A) is None
            await register(fresh, A)
            await report(fresh, A, await subscribe(fresh, A))
            await wait_until(lambda: len(registered()) == 24)
            assert set(registered()) == set(stable_ids)
            expected = Path(scratch) / "fixture-entities.json"
            await hass.async_add_executor_job(expected.write_text, json.dumps(registered()))
    finally:
        await hass.async_stop()


async def verify_restart(scratch, expected):
    hass = await async_setup_hass(RuntimeConfig(config_dir=scratch))
    assert hass is not None
    try:
        await hass.async_start()
        entry = hass.config_entries.async_entries("dormpanel")[0]
        assert entry.state.value == "loaded"
        registry = entity_registry.async_get(hass)
        actual = {entity.unique_id: entity.entity_id for entity in registry.entities.values() if entity.platform == "dormpanel"}
        assert expected == actual
        await wait_until(lambda: hass.states.is_state(actual[f"{A}_connected"], "off"))
        assert hass.states.is_state(actual[f"{A}_media_volume"], "unavailable")
    finally:
        await hass.async_stop()


if __name__ == "__main__":
    if len(sys.argv) > 1:
        phase, scratch, port = sys.argv[1:]
        if phase == "exercise":
            asyncio.run(verify(scratch, int(port)))
        else:
            expected = json.loads((Path(scratch) / "fixture-entities.json").read_text())
            asyncio.run(verify_restart(scratch, expected))
        sys.exit(0)

    with tempfile.TemporaryDirectory(prefix="dormpanel-core-fixture-") as scratch:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        root = Path(scratch)
        shutil.copytree(ROOT, root / "custom_components" / "dormpanel", ignore=shutil.ignore_patterns("__pycache__"))
        (root / "configuration.yaml").write_text(f"frontend:\nhttp:\n  server_host: 127.0.0.1\n  server_port: {port}\n")
        for phase in ("exercise", "restart"):
            subprocess.run([sys.executable, __file__, phase, scratch, str(port)], check=True)
    print(f"PASS HA Core {__version__}: config flow and static module, 24 native entities, confirmed HA service, "
          "two-device isolation, auth, local reports, rename, reload, disconnect/reconnect, removal, restart")
