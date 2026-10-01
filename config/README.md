# Local credentials

Create `%USERPROFILE%\.bmtrader\secrets.json` (Java: `user.home/.bmtrader/secrets.json`)
using `secrets.template.json`. The plugin does not execute JavaScript to load
credentials. Fill the JSON with the same fields used by ViteApp's localStorage:
`massive`, `firebaseConfig`, and `schwab`. Other fields are preserved.

Schwab needs `appKey`, `secret`, `refresh_token`, and `accountHashValue`; an existing
`access_token` and its epoch-millisecond `expires_at` are optional. OAuth uses the
broker response's `expires_in`, refreshes within 60 seconds of expiry, and persists
any rotated refresh token with a flushed temporary file and replacement. A revoked
refresh token still requires Schwab's manual authorization flow. Credentials and
broker response bodies are not printed by these clients.

Massive needs `apiKey`. Firestore needs `projectId`; `apiKey` may be provided for
direct API access. Existing Firestore security rules apply. An API key does not
grant database administrator access. The active browser app currently does not
sign into Firebase Auth; the native client follows that same access model. No
service-account secret is required or assumed. Real write permissions have not
been probed; automated writes use fake transports.

To choose another path, pass Java system property
`-Dbmtrader.secrets=C:\absolute\path\secrets.json`. Keep real credentials outside
the repository. The local filename and temporary credential files are ignored by
git as a second precaution. Do not add real values to the tracked template.

These libraries are implemented and tested. The runtime startup/plugin wiring is
still being migrated; consult `docs/standalone-native-trading-progress.md` before
assuming standalone trading is ready.
