# Local credentials

Create `%USERPROFILE%\.bmtrader\secrets.json` using
[secrets.template.json](secrets.template.json). Java resolves this as
`user.home/.bmtrader/secrets.json`. The plugin reads JSON; it does not execute
`storeSecrets.js`. You supply the real values locally.

| Section / fields | Purpose |
| --- | --- |
| `massive.apiKey` | Massive stock REST history/reference and trade stream |
| `firebaseConfig.projectId`, `apiKey` | Direct Firestore config/state/log access |
| `schwab.appKey`, `secret`, `refresh_token`, `accountHashValue` | Broker OAuth and order/account requests |
| `schwab.access_token`, `expires_at` | Optional current access token and expiry in epoch milliseconds |
| `schwab.redirectUrl` | Your registered OAuth callback URL; template uses https://127.0.0.1 |
| `schwab.accountId` | Preserved for compatibility; requests use accountHashValue |
| `tradingPolicy.coreTargetEnabled` | Optional core-target exit protection, default false |

To choose another location, set Java property
`-Dbmtrader.secrets=C:\absolute\path\secrets.json`. Keep the real JSON outside
this repository and never fill the tracked template with secrets.

After changing the file, use **Restart Native Trading / Reload Secrets** in the
addon settings. Startup reads Firestore, obtains a usable broker token and starts
its own streams/history loader. It logs missing configuration or credential errors.
The default trading policy mirrors the browser: ten partials, $1,000 R,
$4,000 daily loss limit and a single selected stock.

OAuth uses the response's `expires_in`, checks every 30 seconds, and refreshes
within 60 seconds of expiry. Refreshes coalesce; rotated tokens are saved locally
with a flushed temporary file and replacement, preserving unrelated JSON fields.
For manual authorization, use **Open Schwab Authorization**, complete consent,
then paste the final callback URL into **Import Schwab Callback URL**. A successful
exchange saves the tokens and starts/reconnects trading. No local callback server
is needed. A revoked refresh token requires this manual flow.

Run one app at a time. ViteApp stores credentials in its existing browser
localStorage; Bookmap stores them in this JSON. These stores do not synchronize.
When switching apps, supply the current valid broker credentials to the receiving
app if its stored refresh token has become invalid.

Firestore uses the existing database security rules, following the browser's
current unauthenticated access model. An API key does not grant administrator
access. No service-account secret is assumed. Real write permissions have not been
probed; automated tests use fake transports. If rules require Firebase sign-in,
that authentication flow will need implementation in both apps.
