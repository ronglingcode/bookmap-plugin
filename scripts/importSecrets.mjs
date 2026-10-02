import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';

/** Convert the browser provisioning file locally; never print its credential values. */
export function importSecrets(source, destination = resolve(homedir(), 'bmtrader', 'secrets.json')) {
    const captured = new Map();
    try {
        runInNewContext(readFileSync(source, 'utf8'), {
            localStorage: { setItem(key, value) { captured.set(String(key).split('.').at(-1), JSON.parse(value)); } },
        }, { timeout: 1000, contextCodeGeneration: { strings: false, wasm: false } });
    } catch { throw new Error('Cannot read storeSecrets.js; expected a localStorage provisioning script'); }
    const template = JSON.parse(readFileSync(new URL('../config/secrets.template.json', import.meta.url), 'utf8'));
    for (const section of ['massive', 'firebaseConfig', 'schwab']) {
        const values = captured.get(section);
        if (!values || typeof values !== 'object' || Array.isArray(values)) throw new Error(`Missing credential section: ${section}`);
        template[section] = { ...template[section], ...values };
    }
    for (const [section, fields] of [['massive', ['apiKey']], ['firebaseConfig', ['projectId', 'apiKey']], ['schwab', ['appKey', 'secret', 'accountHashValue', 'refresh_token']]]) {
        for (const field of fields) if (typeof template[section][field] !== 'string' || !template[section][field].trim()) throw new Error(`Missing credential field: ${section}.${field}`);
    }
    // The source may omit expiry. Zero tells OAuth to refresh instead of guessing its lifetime.
    if (!Number.isFinite(template.schwab.expires_at)) template.schwab.expires_at = 0;
    destination = resolve(destination);
    mkdirSync(dirname(destination), { recursive: true });
    writeFileSync(destination, JSON.stringify(template, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    return destination;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        if (!process.argv[2]) throw new Error('Usage: node scripts/importSecrets.mjs <storeSecrets.js> [destination]');
        console.log(`Created local credentials: ${importSecrets(resolve(process.argv[2]), process.argv[3])}`);
    } catch (error) {
        console.error(error.code === 'EEXIST' ? 'Destination already exists; existing credentials were left unchanged' : error.message);
        process.exitCode = 1;
    }
}
