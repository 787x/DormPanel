"""Authenticated upload and signed-path download views."""

import json
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.exceptions import Unauthorized

from .const import MAX_BYTES


class UploadView(HomeAssistantView):
    url = "/api/dormpanel/upload"
    name = "api:dormpanel:upload"

    def __init__(self, relay):
        self.relay = relay

    async def post(self, request):
        if not request["hass_user"].is_admin:
            raise Unauthorized()
        if not request.content_type.startswith("multipart/"):
            raise web.HTTPBadRequest(text="Multipart upload required")
        if request.content_length is not None and request.content_length > MAX_BYTES + 65536:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BYTES + 65536, actual_size=request.content_length)
        try:
            reader = await request.multipart()
            fields = {}
            file_data = None
            filename = None
            async for part in reader:
                if part.name == "file" and file_data is None:
                    filename = part.filename
                    chunks = bytearray()
                    while chunk := await part.read_chunk(8192):
                        chunks.extend(chunk)
                        if len(chunks) > MAX_BYTES:
                            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BYTES, actual_size=len(chunks))
                    file_data = bytes(chunks)
                elif part.name in ("targets", "retention") and part.name not in fields:
                    raw = await part.read(decode=True)
                    if len(raw) > 8192:
                        raise ValueError("Metadata too large")
                    fields[part.name] = raw.decode("utf-8")
                else:
                    raise ValueError("Exactly one file and supported fields are required")
            if file_data is None or not filename:
                raise ValueError("Choose an .ics file")
            targets = json.loads(fields["targets"])
            if not isinstance(targets, list) or any(not isinstance(value, str) for value in targets):
                raise ValueError("Invalid targets")
            item = await self.relay.create(filename, file_data, targets, int(fields["retention"]))
            return self.json(item)
        except (ValueError, KeyError, UnicodeError, json.JSONDecodeError) as error:
            raise web.HTTPBadRequest(text=str(error)) from error


class DownloadView(HomeAssistantView):
    url = "/api/dormpanel/transfers/{transfer_id}/{claim_id}"
    name = "api:dormpanel:download"

    def __init__(self, relay):
        self.relay = relay

    async def get(self, request, transfer_id, claim_id):
        if "Range" in request.headers:
            raise web.HTTPBadRequest(text="Range downloads are not supported")
        try:
            item, path = self.relay.downloadable(transfer_id, claim_id)
        except (PermissionError, ValueError):
            raise web.HTTPNotFound()
        if not await self.relay.hass.async_add_executor_job(path.is_file):
            raise web.HTTPNotFound()
        return web.FileResponse(path, headers={
            "Content-Type": "text/calendar", "Content-Length": str(item["size"]),
            "Cache-Control": "no-store",
            "Content-Disposition": f'attachment; filename="{item["filename"].encode("ascii", "ignore").decode() or "timetable.ics"}"',
        })
