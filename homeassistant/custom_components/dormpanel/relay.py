"""Private, bounded transfer store. No calendar parsing happens here."""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import time

from homeassistant.helpers.storage import Store
from homeassistant.helpers.event import async_track_point_in_utc_time

from .const import DOMAIN, EVENT_AVAILABLE, EVENT_CHANGED, MAX_BYTES, RETENTIONS, TERMINAL


def safe_filename(value: str) -> str:
    name = value.replace("\\", "/").split("/")[-1]
    name = re.sub(r"[^\w.() -]", "_", name, flags=re.UNICODE).strip(" .")[:120]
    if not name.lower().endswith(".ics") or name.lower() == ".ics":
        raise ValueError("Choose one .ics file")
    return name


class Relay:
    def __init__(self, hass):
        self.hass = hass
        self.store = Store(hass, 1, f"{DOMAIN}.relay")
        self.directory = Path(hass.config.path(".storage", "dormpanel_transfers"))
        self.screens: dict = {}
        self.transfers: dict = {}
        self.claims: dict = {}
        self.lock = asyncio.Lock()
        self.timer = None

    def _path(self, transfer_id):
        if not re.fullmatch(r"[0-9a-f]{32}", transfer_id):
            raise ValueError("Invalid transfer ID")
        return self.directory / transfer_id

    async def start(self):
        saved = await self.store.async_load() or {}
        self.screens = saved.get("screens", {})
        self.transfers = saved.get("transfers", {})
        await self.hass.async_add_executor_job(self._clean_orphans)
        missing = [key for key, item in self.transfers.items() if item.get("file_available")
                   and not await self.hass.async_add_executor_job(self._path(key).is_file)]
        for key in missing:
            self.transfers.pop(key)
        if missing:
            await self._save()
        await self.cleanup()

    def _clean_orphans(self):
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        expected = {key for key, item in self.transfers.items() if item.get("file_available")}
        for path in self.directory.iterdir():
            if path.is_file() and ((re.fullmatch(r"[0-9a-f]{32}", path.name) and path.name not in expected)
                                   or re.fullmatch(r"[0-9a-f]{32}\.tmp", path.name)):
                path.unlink(missing_ok=True)

    async def _save(self):
        await self.store.async_save({"screens": self.screens, "transfers": self.transfers})

    def _schedule(self):
        if self.timer:
            self.timer()
            self.timer = None
        if self.transfers:
            nearest = min(item["expires_at"] for item in self.transfers.values())
            self.timer = async_track_point_in_utc_time(
                self.hass,
                lambda _: self.hass.async_create_task(self.cleanup()),
                datetime.fromtimestamp(nearest, timezone.utc),
            )

    async def cleanup(self):
        async with self.lock:
            expired = [key for key, item in self.transfers.items() if item["expires_at"] <= time.time()]
            for key in expired:
                self.transfers.pop(key)
                self.claims = {claim: data for claim, data in self.claims.items() if data[0] != key}
                await self.hass.async_add_executor_job(self._path(key).unlink, True)
            if expired:
                await self._save()
                self.changed()
            self._schedule()

    def changed(self):
        self.hass.bus.async_fire(EVENT_CHANGED, {})

    async def register(self, installation_id, secret, name, version, capabilities, admin):
        async with self.lock:
            old = self.screens.get(installation_id)
            digest = hashlib.sha256(secret.encode()).hexdigest()
            if old:
                if not hmac.compare_digest(old["secret_hash"], digest):
                    raise PermissionError("Installation credential mismatch")
            elif not admin:
                raise PermissionError("Administrator connection required for first registration")
            now = int(time.time())
            self.screens[installation_id] = {
                "installation_id": installation_id, "display_name": name,
                "secret_hash": digest, "app_version": version,
                "capabilities": capabilities, "registered_at": old["registered_at"] if old else now,
                "last_seen": now,
            }
            await self._save()
            self.changed()

    def authorize(self, installation_id, secret):
        screen = self.screens.get(installation_id)
        if not screen or not hmac.compare_digest(screen["secret_hash"], hashlib.sha256(secret.encode()).hexdigest()):
            raise PermissionError("Unknown installation or credential")
        return screen

    def pending(self, installation_id, secret):
        self.authorize(installation_id, secret)
        now = time.time()
        return [{"transfer_id": key, "expires_at": item["expires_at"]} for key, item in self.transfers.items()
                if item["expires_at"] > now and item.get("file_available")
                and item["targets"].get(installation_id) not in (None, *TERMINAL)]

    async def create(self, filename, body, target_ids, retention):
        if retention not in RETENTIONS or not 0 < len(body) <= MAX_BYTES:
            raise ValueError("Invalid size or retention")
        filename = safe_filename(filename)
        targets = list(dict.fromkeys(target_ids))
        async with self.lock:
            if not targets or len(targets) > 100 or any(target not in self.screens for target in targets):
                raise ValueError("Choose registered screens")
            transfer_id = secrets.token_hex(16)
            path = self._path(transfer_id)
            await self.hass.async_add_executor_job(self._write_atomic, path, body)
            now = int(time.time())
            item = {"transfer_id": transfer_id, "filename": filename, "sha256": hashlib.sha256(body).hexdigest(),
                    "size": len(body), "created_at": now, "expires_at": now + retention,
                    "targets": {target: "pending" for target in targets}, "file_available": True}
            self.transfers[transfer_id] = item
            try:
                await self._save()
            except Exception:
                self.transfers.pop(transfer_id)
                await self.hass.async_add_executor_job(path.unlink, True)
                raise
            self._schedule()
            self.hass.bus.async_fire(EVENT_AVAILABLE, {"transfer_id": transfer_id,
                "target_installation_ids": targets, "expires_at": item["expires_at"]})
            self.changed()
            return self.public_transfer(item)

    @staticmethod
    def _write_atomic(path, body):
        temporary = path.with_suffix(".tmp")
        try:
            with temporary.open("xb") as stream:
                stream.write(body)
                stream.flush()
                os.fsync(stream.fileno())
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)

    def claim(self, installation_id, secret, transfer_id):
        self.authorize(installation_id, secret)
        item = self.transfers.get(transfer_id)
        if not item or item["expires_at"] <= time.time() or not item.get("file_available") or \
                item["targets"].get(installation_id) in (None, *TERMINAL):
            raise PermissionError("Transfer unavailable")
        now = time.time()
        self.claims = {key: value for key, value in self.claims.items()
                       if value[2] > now and value[:2] != (transfer_id, installation_id)}
        claim_id = secrets.token_hex(16)
        self.claims[claim_id] = (transfer_id, installation_id, now + 60)
        return item, claim_id

    def downloadable(self, transfer_id, claim_id):
        claim = self.claims.get(claim_id)
        item = self.transfers.get(transfer_id)
        if not claim or not item or claim[0] != transfer_id or claim[2] <= time.time() or \
                item["expires_at"] <= time.time() or not item.get("file_available") or \
                item["targets"].get(claim[1]) in (None, *TERMINAL):
            raise PermissionError("Transfer unavailable")
        return item, self._path(transfer_id)

    async def acknowledge(self, installation_id, secret, transfer_id, outcome):
        self.authorize(installation_id, secret)
        async with self.lock:
            item = self.transfers.get(transfer_id)
            if not item or item["expires_at"] <= time.time() or \
                    item["targets"].get(installation_id) is None:
                raise PermissionError("Transfer unavailable")
            if item["targets"][installation_id] in TERMINAL:
                return
            item["targets"][installation_id] = outcome
            if all(value in TERMINAL for value in item["targets"].values()):
                item["file_available"] = False
                await self.hass.async_add_executor_job(self._path(transfer_id).unlink, True)
            await self._save()
            self.changed()

    async def remove_screen(self, installation_id):
        async with self.lock:
            if installation_id not in self.screens:
                raise ValueError("Unknown screen")
            del self.screens[installation_id]
            for item in self.transfers.values():
                if item["targets"].get(installation_id) not in (None, *TERMINAL):
                    item["targets"][installation_id] = "dismissed"
                if item.get("file_available") and all(value in TERMINAL for value in item["targets"].values()):
                    item["file_available"] = False
                    await self.hass.async_add_executor_job(self._path(item["transfer_id"]).unlink, True)
            await self._save()
            self.changed()

    async def cancel(self, transfer_id):
        async with self.lock:
            item = self.transfers.pop(transfer_id, None)
            if not item:
                raise ValueError("Unknown transfer")
            self.claims = {key: value for key, value in self.claims.items() if value[0] != transfer_id}
            await self.hass.async_add_executor_job(self._path(transfer_id).unlink, True)
            await self._save()
            self._schedule()
            self.changed()

    def public_transfer(self, item):
        result = dict(item)
        result["targets"] = [{"installation_id": key,
                              "display_name": self.screens.get(key, {}).get("display_name", key),
                              "state": value} for key, value in item["targets"].items()]
        return result

    def admin_state(self):
        return {"screens": [{key: value for key, value in screen.items() if key != "secret_hash"}
                            for screen in self.screens.values()],
                "transfers": [self.public_transfer(item) for item in
                              sorted(self.transfers.values(), key=lambda item: item["created_at"], reverse=True)]}
