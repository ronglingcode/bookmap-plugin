# Local credentials

SignalComposer rules are defined in tracked Java source and compiled into the JAR;
no composer JSON needs to be copied between machines. The tracked
[rule template](signal-composer.template.json) documents and is tested against all defaults.
A custom rules file is read only with the explicit `bmtrader.signalComposerConfig` JVM property.
See the [operator guide](../docs/signal-composer.md) for the advisory switch, exact defaults,
all-attachment rule reload, and credential-free replay.
The existing `cairo-observation.json` `observerOnly` mode permits observation settings and
attachment without secrets; ordinary missing-secrets attachment remains inactive.

Create `%USERPROFILE%\bmtrader\secrets.json` using
[secrets.template.json](secrets.template.json). Java resolves this as
`user.home/bmtrader/secrets.json`. The plugin reads JSON; it does not execute
`storeSecrets.js`. The tracked template contains field names and defaults only.

At attachment, the plugin checks whether this path (or the explicit override)
is a regular file. If absent, it silently does nothing: no addon UI, indicators,
WebSocket server, vendor requests or trading runtime. Settings return no panels,
and market callbacks and detach are harmless. After creating the file, disable
and re-enable the addon; there is no automatic file watcher. Existing but malformed
or incomplete files retain the normal startup diagnostics. The check establishes
file presence only, without a license or identity check.

To populate the local file from your existing browser provisioning script, run
this once from the `bookmap-plugin` directory:

```powershell
node scripts/importSecrets.mjs ..\secrets\storeSecrets.js
```

This creates `%USERPROFILE%\bmtrader\secrets.json`, copying the Massive, Firebase
and Schwab sections without printing their values. Optional unused vendors are
excluded. It preserves the Firebase fields from the source, adds the registered
redirect URL default and sets missing token expiry to zero so startup refreshes
the token. The importer refuses to overwrite an existing file, including tokens
rotated by the plugin. An optional second argument selects another destination.

For another computer, copy the plugin JAR and this populated local JSON to that
computer's `%USERPROFILE%\bmtrader\secrets.json`. The JSON and its parent folder
must be writable for token renewal. Use the latest JSON when switching computers.

| Section / fields | Purpose |
| --- | --- |
| `massive.apiKey` | Massive stock REST history/reference and trade stream |
| `firebaseConfig.projectId`, `apiKey` | Direct Firestore config/state/log access |
| `schwab.appKey`, `secret`, `refresh_token`, `accountHashValue` | Broker OAuth and order/account requests |
| `schwab.access_token`, `expires_at` | Optional current access token and expiry in epoch milliseconds |
| `schwab.redirectUrl` | Your registered OAuth callback URL; template uses https://127.0.0.1 |
| `schwab.accountId` | Preserved for compatibility; requests use accountHashValue |
| `schwab.token_type` | Preserved from the provisioning file; broker requests use Bearer tokens |
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
