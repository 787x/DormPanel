"""Pure relay-store tests, runnable without a Home Assistant install."""

import asyncio
import copy
import hashlib
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

    async def test_apk_kind_capability_and_terminal_outcomes(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "apk_install_v1"], True)
        temporary = self.relay.directory / ("e" * 32 + ".tmp")
        body = b"APK fixture bytes"
        temporary.write_bytes(body)
        with self.assertRaises(ValueError):
            await self.relay.create_from_file("demo.apk", temporary, len(body), hashlib.sha256(body).hexdigest(),
                                              ["screen-two"], 3600, "apk")
        item = await self.relay.create_from_file("demo.apk", temporary, len(body), hashlib.sha256(body).hexdigest(),
                                                 ["screen-one"], 3600, "apk")
        self.assertEqual("apk", item["kind"])
        restarted = relay_module.Relay(self.hass)
        await restarted.start()
        claimed, claim_id = restarted.claim("screen-one", "first-secret", item["transfer_id"])
        self.assertEqual("apk", claimed["kind"])
        self.assertEqual(body, restarted.downloadable(item["transfer_id"], claim_id)[1].read_bytes())
        with self.assertRaises(ValueError):
            await restarted.acknowledge("screen-one", "first-secret", item["transfer_id"], "imported")
        await restarted.acknowledge("screen-one", "first-secret", item["transfer_id"], "preview_ready")
        self.assertEqual(1, len(restarted.pending("screen-one", "first-secret")))
        await restarted.acknowledge("screen-one", "first-secret", item["transfer_id"], "installed")
        self.assertFalse(restarted._path(item["transfer_id"]).exists())

    async def test_apk_limit_does_not_expand_schedule_limit(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "apk_install_v1"], True)
        temporary = self.relay.directory / ("d" * 32 + ".tmp")
        temporary.write_bytes(b"x")
        with self.assertRaises(ValueError):
            await self.relay.create_from_file("large.ics", temporary, 1024 * 1024 + 1, "a" * 64,
                                              ["screen-one"], 3600, "schedule_ics")
        self.assertTrue(temporary.exists())
        item = await self.relay.create_from_file("large.apk", temporary, 1024 * 1024 + 1, "a" * 64,
                                                 ["screen-one"], 3600, "apk")
        self.assertEqual("apk", item["kind"])

    async def test_schedule_csv_create_claim_ack(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        body = b"csv,timetable\n1,2\n"
        temporary.write_bytes(body)
        item = await self.relay.create_from_file("plan.csv", temporary, len(body), hashlib.sha256(body).hexdigest(),
                                                 ["screen-one"], 3600, "schedule_csv")
        self.assertEqual("schedule_csv", item["kind"])
        self.assertEqual("plan.csv", item["filename"])
        claimed, claim_id = self.relay.claim("screen-one", "first-secret", item["transfer_id"])
        self.assertEqual("schedule_csv", claimed["kind"])
        self.assertEqual(body, self.relay.downloadable(item["transfer_id"], claim_id)[1].read_bytes())
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "preview_ready")
        self.assertEqual(1, len(self.relay.pending("screen-one", "first-secret")))
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "imported")
        self.assertEqual([], self.relay.pending("screen-one", "first-secret"))
        self.assertFalse(self.relay._path(item["transfer_id"]).exists())

    async def test_schedule_csv_filename_must_end_csv(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        temporary.write_bytes(b"x")
        for name in ("plan.ics", "plan.txt", "plan", ".csv"):
            with self.assertRaises(ValueError):
                await self.relay.create_from_file(name, temporary, 1, "a" * 64, ["screen-one"], 3600, "schedule_csv")
        self.assertTrue(temporary.exists())

    async def test_schedule_csv_requires_schedule_csv_v1(self):
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        temporary.write_bytes(b"x")
        with self.assertRaises(ValueError):
            await self.relay.create_from_file("plan.csv", temporary, 1, "a" * 64, ["screen-two"], 3600, "schedule_csv")
        self.assertTrue(temporary.exists())
        await self.relay.register("screen-two", "second-secret", "Desk", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        item = await self.relay.create_from_file("plan.csv", temporary, 1, "a" * 64, ["screen-two"], 3600, "schedule_csv")
        self.assertEqual("schedule_csv", item["kind"])
        self.assertEqual("plan.csv", item["filename"])

    async def test_schedule_csv_size_limit_is_one_mib(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        temporary.write_bytes(b"x")
        with self.assertRaises(ValueError):
            await self.relay.create_from_file("large.csv", temporary, 1024 * 1024 + 1, "a" * 64,
                                              ["screen-one"], 3600, "schedule_csv")
        self.assertTrue(temporary.exists())
        item = await self.relay.create_from_file("ok.csv", temporary, 1024 * 1024, "a" * 64,
                                                 ["screen-one"], 3600, "schedule_csv")
        self.assertEqual("schedule_csv", item["kind"])

    async def test_schedule_csv_outcomes(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        for outcome in ("imported", "dismissed", "rejected_invalid"):
            temporary.write_bytes(b"csv")
            item = await self.relay.create_from_file("plan.csv", temporary, 3, hashlib.sha256(b"csv").hexdigest(),
                                                     ["screen-one"], 3600, "schedule_csv")
            await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], outcome)
            self.assertFalse(self.relay._path(item["transfer_id"]).exists())
        temporary.write_bytes(b"csv")
        item = await self.relay.create_from_file("plan.csv", temporary, 3, hashlib.sha256(b"csv").hexdigest(),
                                                 ["screen-one"], 3600, "schedule_csv")
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "preview_ready")
        self.assertTrue(self.relay._path(item["transfer_id"]).exists())
        for wrong in ("installed", "install_failed", "bogus"):
            with self.assertRaises(ValueError):
                await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], wrong)
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "imported")
        self.assertFalse(self.relay._path(item["transfer_id"]).exists())

    async def test_schedule_ics_filename_rejects_csv(self):
        await self.relay.register("screen-one", "first-secret", "Bedside", "1.0",
                                  ["schedule_relay_v1", "schedule_csv_v1"], True)
        temporary = self.relay.directory / ("c" * 32 + ".tmp")
        temporary.write_bytes(b"x")
        for name in ("plan.csv", "plan.txt", "plan"):
            with self.assertRaises(ValueError):
                await self.relay.create_from_file(name, temporary, 1, "a" * 64, ["screen-one"], 3600, "schedule_ics")
        self.assertTrue(temporary.exists())

    async def test_schedule_ics_remains_compatible(self):
        item = await self.relay.create("folder\\WakeUp.ics", b"calendar", ["screen-one"], 3600)
        self.assertEqual("schedule_ics", item["kind"])
        self.assertEqual("WakeUp.ics", item["filename"])
        claimed, claim_id = self.relay.claim("screen-one", "first-secret", item["transfer_id"])
        self.assertEqual("schedule_ics", claimed["kind"])
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "preview_ready")
        await self.relay.acknowledge("screen-one", "first-secret", item["transfer_id"], "imported")
        self.assertFalse(self.relay._path(item["transfer_id"]).exists())
        await self.relay.register("bare", "bare-secret", "Bare", "1.0", [], True)
        item = await self.relay.create("Bare.ics", b"x", ["bare"], 3600)
        self.assertEqual("schedule_ics", item["kind"])



if __name__ == "__main__":
    unittest.main()
