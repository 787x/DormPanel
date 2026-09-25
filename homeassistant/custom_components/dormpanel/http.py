"""Authenticated upload and signed-path download views."""

import json
import hashlib
import os
import secrets
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.exceptions import Unauthorized

from .const import MAX_BYTES, MAX_APK_BYTES


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
        if request.content_length is not None and request.content_length > MAX_APK_BYTES + 65536:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_APK_BYTES + 65536, actual_size=request.content_length)
        self.relay.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        temporary = self.relay.directory / f"{secrets.token_hex(16)}.tmp"
        try:
            reader = await request.multipart()
            fields = {}
            file_size = 0
            digest = hashlib.sha256()
            filename = None
            async for part in reader:
                if part.name == "file" and filename is None:
                    filename = part.filename
                    kind = fields.get("kind", "schedule_ics")
                    limit = MAX_APK_BYTES if kind == "apk" else MAX_BYTES
                    with temporary.open("xb") as stream:
                        while chunk := await part.read_chunk(65536):
                            file_size += len(chunk)
                            if file_size > limit:
                                raise web.HTTPRequestEntityTooLarge(max_size=limit, actual_size=file_size)
                            digest.update(chunk)
                            await self.relay.hass.async_add_executor_job(stream.write, chunk)
                        await self.relay.hass.async_add_executor_job(stream.flush)
                        await self.relay.hass.async_add_executor_job(os.fsync, stream.fileno())
                elif part.name in ("targets", "retention", "kind") and part.name not in fields:
                    raw = await part.read(decode=True)
                    if len(raw) > 8192:
                        raise ValueError("Metadata too large")
                    fields[part.name] = raw.decode("utf-8")
                else:
                    raise ValueError("Exactly one file and supported fields are required")
            kind = fields.get("kind", "schedule_ics")
            limit = MAX_APK_BYTES if kind == "apk" else MAX_BYTES
            if not filename or not file_size or file_size > limit:
                raise ValueError("Choose one file within its size limit")
            targets = json.loads(fields["targets"])
            if not isinstance(targets, list) or any(not isinstance(value, str) for value in targets):
                raise ValueError("Invalid targets")
            item = await self.relay.create_from_file(filename, temporary, file_size, digest.hexdigest(),
                                                     targets, int(fields["retention"]), kind)
            return self.json(item)
        except (ValueError, KeyError, UnicodeError, json.JSONDecodeError) as error:
            raise web.HTTPBadRequest(text=str(error)) from error
        finally:
            temporary.unlink(missing_ok=True)


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
            "Content-Type": "application/vnd.android.package-archive" if item.get("kind") == "apk" else "text/calendar", "Content-Length": str(item["size"]),
            "Cache-Control": "no-store",
            "Content-Disposition": f'attachment; filename="{item["filename"].encode("ascii", "ignore").decode() or "timetable.ics"}"',
        })
