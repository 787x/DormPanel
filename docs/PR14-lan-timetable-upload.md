# PR14 — LAN timetable upload

The Timetable page offers **Receive from phone/computer** beside local ICS import and imported sources. Opening it creates one temporary HTTP listener on a dynamic port. The displayed URL contains a fresh 192-bit random capability token; the QR code encodes that exact URL. The listener expires after 10 minutes or closes when the user stops, the Activity is destroyed, or one valid upload is accepted.

The server accepts only `GET /<token>/` and `POST /<token>/upload`. It serves one self-contained HTML form. Request headers are limited to 8 KiB, the multipart body to 1 MiB plus 16 KiB of framing, and the ICS file to `ImportLimits.BYTES`. At most two client threads exist and only one upload can be processed at once. It creates a `ScheduleArtifact` with `kind = "lan_upload"` and a token-free locator. The UI adapter runs `IcsScheduleImporter` on the server worker thread and opens the existing preview after validation; no database write occurs before confirmation. Existing source replacement rules, including WebDAV source protection, remain in the preview.

The address chooser uses active, private IPv4 interface addresses, prioritizing Wi-Fi and Ethernet and excluding loopback, link-local, virtual, VPN tunnel, and cellular interfaces. If no usable address exists, the UI offers Retry instead of a QR URL. The server uses no additional Android permission, service, wake lock, discovery, or polling while idle.

The QR is generated locally with ZXing core 3.5.4, black on white with a four-module quiet zone. The dark UI keeps the QR area white for scanning.

## Verification

`assembleDebug`, `testDebugUnitTest` (150 tests), and `lintDebug` passed. Automated coverage includes token lifecycle and routes, multipart validation, request and file size limits, concurrent upload rejection, WakeUp ICS parsing through the existing importer, and QR decode back to the displayed URL. The existing PR1–13 tests passed in the full `connectedDebugAndroidTest` run on X08E using the documented command-line-only built-in test platform flag.

Physical device: X08E (Android 9/API 28, 1280×800, density 160) connected by Wi-Fi to the development computer's hotspot. The computer reached the device directly on the hotspot network. Edge loaded the tokenized HTML form. The QR captured from the physical screen decoded to exactly the selectable URL shown beside it. A computer multipart upload of the WakeUp fixture returned success and opened the normal device preview showing 7 series and 175 classes; cancelling left imported sources empty. Malformed ICS returned HTTP 422 while the same session stayed usable. Stop and successful upload each closed the old URL. A separate session ran to its 10-minute timeout: the device showed an expiry notice and the old URL stopped accepting connections. A separate phone camera scan was not available during this verification. Private IP addresses and session tokens are intentionally omitted from this document.
