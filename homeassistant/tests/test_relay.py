"""Pure relay-store tests, runnable without a Home Assistant install."""

import asyncio
import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest


ROOT = Path(__file__).resolve().parents[1] / "custom_components" / "dormpanel"


class FakeStore:
    data = {}

    def __init__(self, hass, version, key):
        self.key = key

    async def async_load(self):
        return copy.deepcopy(self.data.get(self.key))

    async def async_save(self, value):
        self.data[self.key] = copy.deepcopy(value)


def load_relay():
    package = types.ModuleType("dormpanel_pure")
    package.__path__ = [str(ROOT)]
    sys.modules[package.__name__] = package
    for name in ("homeassistant", "homeassistant.helpers", "homeassistant.helpers.storage", "homeassistant.helpers.event"):
        sys.modules[name] = types.ModuleType(name)
    sys.modules["homeassistant.helpers.storage"].Store = FakeStore
    sys.modules["homeassistant.helpers.event"].async_track_point_in_utc_time = lambda *args: lambda: None
    for module in ("const", "relay"):
        spec = importlib.util.spec_from_file_location(f"dormpanel_pure.{module}", ROOT / f"{module}.py")
        loaded = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = loaded
        spec.loader.exec_module(loaded)
    return sys.modules["dormpanel_pure.relay"]


relay_module = load_relay()


class FakeHass:
    def __init__(self, root):
        self.config = types.SimpleNamespace(path=lambda *parts: str(Path(root).joinpath(*parts)))
        self.bus = types.SimpleNamespace(async_fire=lambda *args: None)

    async def async_add_executor_job(self, func, *args):
        return func(*args)

    def async_create_task(self, coro):
        return asyncio.create_task(coro)


class RelayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        FakeStore.data.clear()
        self.temp = tempfile.TemporaryDirectory()
        self.hass = FakeHass(self.temp.name)
        self.relay = relay_module.Relay(self.hass)
        await self.relay.start()
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0", ["schedule_relay_v1"], True)
        await self.relay.register("screen-two", "second-secret", "Desk", "1.0", ["schedule_relay_v1"], True)

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_registration_and_secret(self):
        with self.assertRaises(PermissionError):
            await self.relay.register("screen-one", "wrong", "Impostor", "1.0", [], True)
        with self.assertRaises(PermissionError):
            await self.relay.register("new", "secret", "New", "1.0", [], False)
        self.assertNotIn("first-secret", repr(self.relay.admin_state()))
        self.assertEqual("Bedside", self.relay.screens["screen-one"]["display_name"])

    async def test_multi_target_pending_claim_ack_restart(self):
        item = await self.relay.create("folder\\WakeUp.ics", b"calendar", ["screen-one", "screen-two"], 3600)
        identifier = item["transfer_id"]
        self.assertEqual("WakeUp.ics", item["filename"])
        self.assertEqual(1, len(self.relay.pending("screen-one", "first-secret")))
        with self.assertRaises(PermissionError):
            self.relay.claim("screen-two", "wrong", identifier)
        transfer, claim = self.relay.claim("screen-one", "first-secret", identifier)
        self.assertEqual(transfer["size"], 8)
        self.assertEqual(identifier, self.relay.downloadable(identifier, claim)[0]["transfer_id"])
        await self.relay.acknowledge("screen-one", "first-secret", identifier, "preview_ready")
        self.assertEqual(1, len(self.relay.pending("screen-one", "first-secret")))
        restarted = relay_module.Relay(self.hass)
        await restarted.start()
        self.assertEqual(1, len(restarted.pending("screen-two", "second-secret")))
        await restarted.acknowledge("screen-one", "first-secret", identifier, "imported")
        self.assertEqual([], restarted.pending("screen-one", "first-secret"))
        self.assertTrue(restarted._path(identifier).exists())
        await restarted.acknowledge("screen-two", "second-secret", identifier, "dismissed")
        self.assertFalse(restarted._path(identifier).exists())

    async def test_limits_cancellation_and_expiry(self):
        for name, body in (("../evil.txt", b"x"), ("okay.ics", b"x" * (1024 * 1024 + 1))):
            with self.assertRaises(ValueError):
                await self.relay.create(name, body, ["screen-one"], 3600)
        item = await self.relay.create("okay.ics", b"x", ["screen-one"], 3600)
        identifier = item["transfer_id"]
        with self.assertRaises(PermissionError):
            self.relay.claim("screen-two", "second-secret", identifier)
        self.assertEqual([], self.relay.pending("screen-two", "second-secret"))
        await self.relay.cancel(identifier)
        self.assertFalse(self.relay._path(identifier).exists())
        with self.assertRaises(PermissionError):
            self.relay.claim("screen-one", "first-secret", identifier)
        item = await self.relay.create("okay.ics", b"x", ["screen-one"], 3600)
        identifier = item["transfer_id"]
        _, claim = self.relay.claim("screen-one", "first-secret", identifier)
        self.relay.transfers[identifier]["expires_at"] = 0
        with self.assertRaises(PermissionError):
            self.relay.claim("screen-one", "first-secret", identifier)
        with self.assertRaises(PermissionError):
            self.relay.downloadable(identifier, claim)
        await self.relay.cleanup()
        self.assertFalse(self.relay._path(identifier).exists())
        self.assertEqual([], self.relay.pending("screen-one", "first-secret"))

    async def test_startup_cleans_orphan_and_missing_file_metadata(self):
        item = await self.relay.create("okay.ics", b"x", ["screen-one"], 3600)
        identifier = item["transfer_id"]
        self.relay._path(identifier).unlink()
        orphan = self.relay.directory / ("f" * 32 + ".tmp")
        orphan.write_bytes(b"incomplete")
        restarted = relay_module.Relay(self.hass)
        await restarted.start()
        self.assertFalse(orphan.exists())
        self.assertEqual([], restarted.pending("screen-one", "first-secret"))
        self.assertNotIn(identifier, restarted.transfers)


if __name__ == "__main__":
    unittest.main()
