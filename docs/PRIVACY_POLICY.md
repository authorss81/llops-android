# Privacy Policy for NoteFlow

**Last Updated: August 8, 2026**

NoteFlow ("the App") is a privacy-first, offline-capable note-taking and knowledge management application for Android.

## 1. Zero Cloud Tracking & Local Data Ownership
- All notes, drawings, ink strokes, audio recordings, and knowledge graphs created within NoteFlow are stored exclusively on your local device.
- All database records are protected with SQLCipher hardware/software backed AES-256-GCM encryption wrapped with keys stored in the Android KeyStore or PBKDF2 derivative.
- NoteFlow does NOT operate central servers, track analytics, harvest personal identifiers, or profile users.

## 2. Optional WebDAV / Sync Integrations
- If you explicitly choose to configure WebDAV or Nextcloud sync, encrypted vault backup archives are transmitted directly between your device and your chosen personal WebDAV endpoint over HTTPS.
- Backups are encrypted at rest (SQLCipher + AES-256-GCM) and sent with HTTPS required. This is NOT zero-knowledge: the WebDAV server operator can read the backup files, but not your note plaintext.
- No third-party or developer servers receive your sync credentials or data.

## 3. Permissions Requested & Justifications
- **RECORD_AUDIO**: Required only when you record voice notes inside the App. Voice notes are stored locally and transcribed on-device.
- **INTERNET**: Used solely for optional WebDAV / Nextcloud backup sync to your personal server (HTTPS required).
- **READ_EXTERNAL_STORAGE / READ_MEDIA_** : Required only when importing existing Markdown notes, images, or PDFs into your local notebook.

## 4. Children's Privacy
NoteFlow does not knowingly collect or solicit personal data from children under 13.

## 5. Contact Us
For questions regarding this Privacy Policy, please open an issue at:
`https://github.com/authorss81/Smooth-Notes`
