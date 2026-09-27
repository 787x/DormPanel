# PR26 — Settings and phone entry

Home Assistant settings show status, URL, token, **Fill from phone**, the HTTP warning, **Preferred weather**, and the normal Test, Save / Reconnect, and Clear actions. **Advanced settings** starts collapsed and contains backend mode, relay identity/status, all helper bindings, and the helper sync note. Expanding or collapsing does not write configuration; Save reads the existing selected values even while Advanced is hidden. Preferred weather stays directly available.

For first-time HA setup, saving a new URL and token enables the HA backend without opening Advanced. Existing backend choices are preserved on later saves.

Home Assistant and WebDAV settings can each start a ten-minute, one-shot LAN form. The X08E displays a QR code and selectable URL. A phone on the same trusted LAN can enter the URL and secret. Submission updates the current Android fields only; Test and Save remain explicit Android actions. Closing the settings dialog discards unsaved entries and stops the server. No suitable private IPv4 address means no server starts.

**Use only on a trusted local network. Credentials are sent directly to this DormPanel over the LAN.** The form uses plain HTTP, not encrypted transport. Its random capability URL contains no credential; secrets travel in the POST body. The browser never receives the saved HA token or WebDAV password. The server has bounded requests, one accepted submission, an expiry, and no persistent storage. Existing Android Keystore encrypted credential storage and its aliases remain unchanged. The timetable upload server and QR behavior are retained.
