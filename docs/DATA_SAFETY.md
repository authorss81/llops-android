# Google Play Data Safety Declaration for NoteFlow

| Data Category | Data Collected | Data Shared | Purpose | Security |
| :--- | :--- | :--- | :--- | :--- |
| **Personal Info** | None | None | N/A | N/A |
| **Files & Docs** | Stored locally | None (unless WebDAV sync used) | Note creation & organization | AES-256-GCM Encrypted at rest |
| **Audio** | Stored locally | None | Voice note recording & local transcript | Encrypted at rest |
| **App Performance & Logs** | Crash logs (Local only) | None | Technical debugging | Scrubbed / Sanitized on-device |

## Security Practices
- Data is encrypted in transit (TLS 1.3 for WebDAV) and encrypted at rest (SQLCipher + AES-256-GCM).
- Users can delete all notes, vaults, and application data at any time directly from app settings or device settings.
