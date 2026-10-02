import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, unlinkSync, rmdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { importSecrets } from './importSecrets.mjs';

test('imports required vendors and forces expiry refresh without overwriting rotated credentials', () => {
    const directory = mkdtempSync(join(tmpdir(), 'bmtrader-import-test-'));
    try {
        const source = join(directory, 'storeSecrets.js'), destination = join(directory, 'bmtrader', 'secrets.json');
        const values = { massive: { apiKey: 'test-massive' }, firebaseConfig: { projectId: 'test-project', apiKey: 'test-firebase', authDomain: 'test-domain' }, schwab: { appKey: 'test-app', secret: 'test-secret', accountHashValue: 'test-account', refresh_token: 'test-refresh', access_token: 'test-access' }, openai: { apiKey: 'unused' } };
        writeFileSync(source, Object.entries(values).map(([key, value]) => `localStorage.setItem('tradingscripts.${key}', JSON.stringify(${JSON.stringify(value)}));`).join('\n'));
        assert.equal(importSecrets(source, destination), destination);
        const imported = JSON.parse(readFileSync(destination, 'utf8'));
        assert.equal(imported.schwab.refresh_token, 'test-refresh');
        assert.equal(imported.schwab.expires_at, 0);
        assert.equal(imported.schwab.redirectUrl, 'https://127.0.0.1');
        assert.equal(imported.firebaseConfig.authDomain, 'test-domain');
        assert.equal(imported.massive.apiKey, 'test-massive');
        assert.equal(imported.openai, undefined);
        imported.schwab.refresh_token = 'rotated'; writeFileSync(destination, JSON.stringify(imported));
        assert.throws(() => importSecrets(source, destination), { code: 'EEXIST' });
        assert.equal(JSON.parse(readFileSync(destination, 'utf8')).schwab.refresh_token, 'rotated');
    } finally {
        for (const file of [join(directory, 'storeSecrets.js'), join(directory, 'bmtrader', 'secrets.json')]) if (existsSync(file)) unlinkSync(file);
        if (existsSync(join(directory, 'bmtrader'))) rmdirSync(join(directory, 'bmtrader'));
        rmdirSync(directory);
    }
});
