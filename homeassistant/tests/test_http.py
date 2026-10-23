"""Upload/download view checks with HA boundary stubs; no live HAOS claim."""
import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path
import test_relay
ROOT = test_relay.ROOT
from aiohttp import web

class Unauthorized(Exception):
    pass

class View:
    @staticmethod
    def json(value):
        return value

for name in ('homeassistant.components', 'homeassistant.components.http', 'homeassistant.exceptions'):
    sys.modules[name] = types.ModuleType(name)
sys.modules['homeassistant.components.http'].HomeAssistantView = View
sys.modules['homeassistant.exceptions'].Unauthorized = Unauthorized
spec = importlib.util.spec_from_file_location('dormpanel_pure.http', ROOT / 'http.py')
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)

class Part:
    def __init__(self, name, data, filename=None):
        self.name, self.data, self.filename = name, data, filename
    async def read(self, **kwargs):
        return self.data
    async def read_chunk(self, size):
        chunk, self.data = self.data[:size], self.data[size:]
        return chunk

class Request(dict):
    content_type = 'multipart/form-data'
    content_length = None
    headers = {}
    def __init__(self, parts, admin=True):
        super().__init__(hass_user=types.SimpleNamespace(is_admin=admin))
        self.parts = parts
    async def multipart(self):
        async def parts():
            for part in self.parts:
                yield part
        return parts()

class HttpXlsTest(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = test_relay.RelayTest.asyncSetUp
    asyncTearDown = test_relay.RelayTest.asyncTearDown
    def request(self, filename='plan.xls', kind='schedule_xls', target='screen-one', body=b'binary', retention='3600', admin=True):
        return Request([Part('kind', kind.encode()), Part('targets', json.dumps([target]).encode()),
                        Part('retention', retention.encode()), Part('file', body, filename)], admin)

    async def test_xls_http_permissions_limits_and_download(self):
        await self.relay.register('screen-one', 'first-secret', 'Bedside', '0.1.8', ['schedule_xls_v1'], True)
        view = http.UploadView(self.relay)
        with self.assertRaises(Unauthorized):
            await view.post(self.request(admin=False))
        for kwargs in ({'target':'screen-two'}, {'filename':'plan.xlsx'}, {'kind':'schedule_csv'},
                       {'kind':'schedule_ics'}, {'retention':'2'}, {'target':'missing'}):
            with self.assertRaises(web.HTTPBadRequest):
                await view.post(self.request(**kwargs))
        with self.assertRaises(web.HTTPRequestEntityTooLarge):
            await view.post(self.request(body=b'x' * (1024 * 1024 + 1)))
        body = (ROOT.parents[2] / 'schedule-core/src/test/resources/schedule/hubei_2026-2027-1.xls').read_bytes()
        item = await view.post(self.request(body=body))
        identifier = item['transfer_id']
        _, token = self.relay.claim('screen-one', 'first-secret', identifier)
        response = await http.DownloadView(self.relay).get(Request([]), identifier, token)
        self.assertEqual('application/vnd.ms-excel', response.headers['Content-Type'])
        self.assertEqual(str(len(body)), response.headers['Content-Length'])
        self.assertEqual(body, self.relay._path(identifier).read_bytes())
        self.assertFalse(list(self.relay.directory.glob('*.tmp')))
