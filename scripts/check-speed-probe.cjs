#!/usr/bin/env node
// Controlled browser test for the bundled engine's unvalidated-network branch.
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('../routebook/node_modules/playwright');

const assetDir = path.resolve(__dirname, '../app/src/main/assets/speed');
const localOrigin = 'https://speed-test.dudu.invalid';
const endpoint = 'https://speed.cloudflare.com';
const assetMime = {
    '/index.html': 'text/html',
    '/measurement.js': 'text/javascript',
    '/cloudflare-speedtest-1.17.0.js': 'text/javascript'
};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function probe(browser, firstResponse) {
    const context = await browser.newContext();
    const page = await context.newPage();
    const traffic = [];
    try {
        await page.route('**/*', async route => {
            const request = route.request();
            const url = new URL(request.url());
            if (url.origin === localOrigin && Object.hasOwn(assetMime, url.pathname)) {
                await route.fulfill({
                    path: path.join(assetDir, url.pathname),
                    contentType: assetMime[url.pathname],
                    headers: {
                        'Content-Security-Policy': "default-src 'none'; script-src 'self'; connect-src "
                            + endpoint + '/__down ' + endpoint + '/__up'
                    }
                });
                return;
            }
            traffic.push([url.pathname, Number(url.searchParams.get('bytes'))]);
            if (url.origin !== endpoint || !['/__down', '/__up'].includes(url.pathname)) {
                await route.abort();
                return;
            }
            const first = traffic.length === 1;
            if (first && firstResponse === 'hang') {
                await delay(4500);
                await route.abort().catch(() => {});
                return;
            }
            const status = first && firstResponse === 'http_error' ? 503 : 200;
            await delay(28);
            await route.fulfill({
                status,
                body: status === 200 && request.method() === 'GET'
                    ? Buffer.alloc(Number(url.searchParams.get('bytes'))) : '',
                headers: {
                    'Access-Control-Allow-Origin': localOrigin,
                    'Timing-Allow-Origin': localOrigin,
                    'Cache-Control': 'no-store'
                }
            });
        });
        await page.goto(localOrigin + '/index.html');
        const start = Date.now();
        const events = await page.evaluate(({ endpoint, localOrigin }) => new Promise((resolve, reject) => {
            const channel = new MessageChannel();
            const output = [];
            const watchdog = setTimeout(() => reject(new Error('Probe fixture timed out')), 6000);
            channel.port1.onmessage = ({ data }) => {
                const message = JSON.parse(data);
                output.push(message);
                if (message.event === 'error' || message.event === 'result') {
                    clearTimeout(watchdog);
                    resolve(output);
                }
            };
            window.postMessage(endpoint + '\ntrue', localOrigin, [channel.port2]);
        }), { endpoint, localOrigin });
        return { events, traffic, duration: Date.now() - start };
    } finally {
        await context.close();
    }
}

async function main() {
    const browser = await chromium.launch({ channel: 'chrome' });
    try {
        for (const mode of ['http_error', 'hang']) {
            const result = await probe(browser, mode);
            assert.equal(result.events.at(-1).reason, 'UNVALIDATED');
            assert.deepEqual(result.traffic, [['/__down', 0]]);
            if (mode === 'hang') assert(result.duration >= 2800 && result.duration < 4400);
            console.log('PASS unvalidated network, single failed probe (' + mode + ')');
        }

        const result = await probe(browser, 'success');
        assert.equal(result.events.at(-1).event, 'result');
        assert.deepEqual(result.traffic.slice(0, 5), Array.from({ length: 5 }, () => ['/__down', 0]));
        assert(!result.traffic.slice(5).some(([, bytes]) => bytes === 0));
        for (const direction of ['/__down', '/__up']) {
            const payload = result.traffic.filter(([route]) => route === direction)
                .reduce((total, [, bytes]) => total + bytes, 0);
            assert(payload > 0 && payload <= 16 * 1024 * 1024);
        }
        assert(result.traffic.every(([route]) => ['/__down', '/__up'].includes(route)));
        console.log('PASS unvalidated network, successful probe counted as one of five pings');
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
