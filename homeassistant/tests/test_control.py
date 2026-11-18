"""Native control tests over the existing relay registration and storage boundary."""

import asyncio
from unittest.mock import patch
import unittest

import test_relay
from dormpanel_pure import control
from dormpanel_pure.control import CAPABILITY, ControlError, NUMBERS, SWITCHES



class ControlTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = test_relay.RelayTest.asyncSetUp
    asyncTearDown = test_relay.RelayTest.asyncTearDown

    async def setup_screen(self, name="screen", capabilities=(CAPABILITY,)):
        await self.relay.register(name, "secret-" + name, name, "1.0", list(capabilities), True)

    def set_up_session(self, name="screen"):
        connection, events = object(), asyncio.Queue()
        session = self.relay.controls.open(name, "secret-" + name, connection, events.put_nowait)
        return session, connection, events

    def report(self, session, name="screen", values=None, permission=True, mute=False, **receipt):
        self.relay.controls.report(name, session.token, session.connection,
            self.values if values is None else values, permission, mute, **receipt)

    values = {**{key: 50 for key in NUMBERS}, **dict.fromkeys(SWITCHES, False), "theme": "dark"}

    async def test_device_registration_reconnect_downgrade_and_removal(self):
        controls = self.relay.controls
        registered = []
        controls.listen_registration(registered.append)
        await self.setup_screen()
        await self.setup_screen("legacy", ("schedule_relay_v1",))
        self.assertEqual(registered, ["screen", "legacy"])
        self.assertFalse(controls.supports("legacy"))
        with self.assertRaises(PermissionError):
            controls.open("screen", "wrong", object(), lambda _: None)
        with self.assertRaisesRegex(ControlError, "unsupported"):
            controls.open("legacy", "secret-legacy", object(), lambda _: None)

        session, _, _ = self.set_up_session()
        self.assertFalse(controls.public_state("screen")["online"])
        self.report(session)
        self.assertTrue(controls.public_state("screen")["online"])
        self.assertNotIn("secret", str(self.relay.admin_state()))
        self.assertNotIn(session.token, str(self.relay.admin_state()))

        new_session, _, _ = self.set_up_session()
        controls.close("screen", session)
        with self.assertRaisesRegex(ControlError, "offline"):
            self.report(session)
        self.report(new_session, values={**self.values, "theme": "light"})
        self.assertEqual(controls.public_state("screen")["values"]["theme"], "light")
        await self.setup_screen(capabilities=("schedule_relay_v1",))
        self.assertNotIn("screen", controls.sessions)
        await self.setup_screen()
        fresh, _, _ = self.set_up_session()
        self.report(fresh)
        await self.relay.remove_screen("screen")
        self.assertNotIn("screen", controls.sessions)
        self.assertFalse(controls.supports("screen"))
        await self.setup_screen()
        self.assertEqual(controls.public_state("screen")["reasons"]["theme"], "offline")

    async def test_all_control_types_return_actual_state_and_isolate_connections(self):
        controls = self.relay.controls
        await self.setup_screen()
        await self.setup_screen("other")
        screen, _, events = self.set_up_session()
        other, _, other_events = self.set_up_session("other")
        self.report(screen)
        self.report(other, name="other")
        values = dict(self.values)
        for field, value in {**dict.fromkeys(NUMBERS, 40), **dict.fromkeys(SWITCHES, True), "theme": "light"}.items():
            task = asyncio.create_task(controls.execute("screen", field, value))
            command = await asyncio.wait_for(events.get(), 1)
            self.assertEqual(command["installation_id"], "screen")
            self.assertEqual(command["field"], field)
            self.assertEqual(command["value"], value)
            self.assertTrue(other_events.empty())
            self.report(other, name="other", command_id=command["command_id"])
            self.assertFalse(task.done())
            with self.assertRaisesRegex(ControlError, "offline"):
                controls.report("screen", screen.token, other.connection, values, True, False)
            with self.assertRaisesRegex(ControlError, "in progress"):
                await controls.execute("screen", "theme", "light")
            actual = 47 if field == "media_volume" else value
            values[field] = actual
            self.report(screen, values=values, command_id=command["command_id"])
            self.assertFalse(task.done())
            with self.assertRaisesRegex(ControlError, "offline"):
                controls.admit("screen", screen.token, other.connection, command["command_id"])
            self.assertGreater(controls.admit("screen", screen.token, screen.connection, command["command_id"]), 0)
            self.report(screen, values=values, command_id=command["command_id"])
            self.assertEqual(await task, actual)
        self.report(screen, values={**values, "theme": "dark"})
        self.assertEqual(controls.public_state("screen")["values"]["theme"], "dark")
        self.assertEqual(controls.public_state("other")["values"], self.values)

    async def test_permissions_dependencies_type_and_device_rejection(self):
        controls = self.relay.controls
        await self.setup_screen()
        session, _, events = self.set_up_session()
        self.report(session, permission=False)
        with self.assertRaisesRegex(ControlError, "permission"):
            await controls.execute("screen", "system_brightness", 30)
        with self.assertRaisesRegex(ControlError, "unsupported"):
            await controls.execute("screen", "media_volume", 0)
        for field, value in [("display_brightness", True), ("blackout", 1), ("theme", "auto"), ("card_opacity", 101)]:
            with self.assertRaisesRegex(ControlError, "Invalid"):
                await controls.execute("screen", field, value)
        self.report(session, values={**self.values, "system_automatic": True, "follow_system": True})
        with self.assertRaisesRegex(ControlError, "automatic"):
            await controls.execute("screen", "system_brightness", 30)
        with self.assertRaisesRegex(ControlError, "following"):
            await controls.execute("screen", "display_brightness", 30)
        self.assertTrue(events.empty())
        task = asyncio.create_task(controls.execute("screen", "system_automatic", False))
        command = await asyncio.wait_for(events.get(), 1)
        controls.admit("screen", session.token, session.connection, command["command_id"])
        self.report(session, permission=False, command_id=command["command_id"], error="permission")
        with self.assertRaisesRegex(ControlError, "permission"):
            await task

    async def test_timeout_disconnect_and_reload(self):
        controls = self.relay.controls
        await self.setup_screen()
        session, _, events = self.set_up_session()
        self.report(session)
        with patch.object(control, "COMMAND_TIMEOUT", 0.02):
            with self.assertRaisesRegex(ControlError, "in time"):
                await controls.execute("screen", "blackout", True)
        expired = await asyncio.wait_for(events.get(), 1)
        with self.assertRaisesRegex(ControlError, "in time"):
            controls.admit("screen", session.token, session.connection, expired["command_id"])
        task = asyncio.create_task(controls.execute("screen", "blackout", True))
        await asyncio.wait_for(events.get(), 1)
        self.relay.pause()
        with self.assertRaisesRegex(ControlError, "offline"):
            await task
        self.assertFalse(controls.public_state("screen")["online"])
        self.relay.active = True
        self.assertTrue(controls.public_state("screen")["online"])
        task = asyncio.create_task(controls.execute("screen", "blackout", True))
        await asyncio.wait_for(events.get(), 1)
        controls.close("screen", session)
        with self.assertRaisesRegex(ControlError, "offline"):
            await task
        restarted = test_relay.relay_module.Relay(self.hass)
        await restarted.start()
        self.assertTrue(restarted.controls.supports("screen"))
        self.assertFalse(restarted.controls.public_state("screen")["online"])
