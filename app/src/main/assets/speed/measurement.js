import SpeedTest from "./cloudflare-speedtest-1.17.0.js";

const MiB = 1024 * 1024;
const requestLimitMs = 10_000;
const probeLimitMs = 3_000;
const totalLimitMs = 45_000;
const phase = { latency: "PING", download: "DOWNLOAD", upload: "UPLOAD" };

window.addEventListener("message", ({ data, ports }) => {
    if (ports.length !== 1) return;
    const [origin, probe] = data.split("\n");
    const endpoint = new URL(origin);
    const isCloudflare = endpoint.origin === "https://speed.cloudflare.com";
    const isFixture = endpoint.protocol === "https:" && endpoint.hostname === "127.0.0.1";
    if (origin !== endpoint.origin || (!isCloudflare && !isFixture)) return;
    run(origin, probe === "true", ports[0]);
}, { once: true });

function run(origin, needProbe, port) {
    let stopped = false;
    let engine;
    const remaining = { down: 16 * MiB, up: 16 * MiB };
    let pings = 0;
    const send = (event, fields = {}) => {
        if (!stopped) port.postMessage(JSON.stringify({ event, ...fields }));
    };
    const stop = () => {
        stopped = true;
        engine?.pause();
        port.close();
    };
    const fail = (reason) => {
        send("error", { reason });
        stop();
    };
    port.onmessage = ({ data }) => {
        if (data === "stop") stop();
    };

    // The engine can retry 429s. Reserve payload for each invocation before allowing it onto
    // the network. Failed, cancelled or partially transferred requests never refund the budget.
    const doFetch = window.fetch.bind(window);
    window.fetch = async (input, options = {}) => {
        if (stopped) throw new DOMException("Measurement stopped", "AbortError");
        const url = new URL(input);
        const isDown = url.pathname === "/__down" && (options.method ?? "GET") === "GET";
        const isUp = url.pathname === "/__up" && options.method === "POST";
        const size = url.searchParams.get("bytes");
        if (url.origin !== origin || url.username || url.password || url.hash
                || (!isDown && !isUp) || !size || !/^\d{1,8}$/.test(size)
                || [...url.searchParams.keys()].some(key => key !== "bytes" && key !== "during")) {
            fail("ENGINE");
            throw new Error("Unexpected measurement request");
        }

        const direction = isDown ? "down" : "up";
        const bytes = Number(size);
        if (bytes > remaining[direction] || (isDown && bytes === 0 && ++pings > 5)) {
            fail("DATA_LIMIT");
            throw new Error("Measurement budget exhausted");
        }
        if (isUp && (typeof options.body !== "string" || options.body.length !== bytes)) {
            fail("ENGINE");
            throw new Error("Unexpected upload size");
        }
        remaining[direction] -= bytes;

        const config = {
            ...options, credentials: "omit", redirect: "error", cache: "no-store",
            referrerPolicy: "no-referrer"
        };
        if (needProbe) {
            // The first of five pings doubles as the reachability check during Android validation.
            // No other measurement can begin unless this short empty request succeeds.
            const controller = new AbortController();
            const abort = () => controller.abort();
            options.signal.addEventListener("abort", abort, { once: true });
            const deadline = setTimeout(abort, probeLimitMs);
            try {
                const response = await doFetch(url.href, { ...config, signal: controller.signal });
                if (!response.ok || !isDown || bytes !== 0) throw new Error("Probe failed");
                if (response.body) {
                    const reader = response.body.getReader();
                    const { done } = await reader.read();
                    if (!done) {
                        void reader.cancel().catch(() => {});
                        throw new Error("Unexpected probe body");
                    }
                }
                needProbe = false;
                send("stage", { stage: "PING" });
                return new Response(null, {
                    headers: response.headers, status: response.status, statusText: response.statusText
                });
            } catch (error) {
                abort();
                if (!stopped) fail("UNVALIDATED");
                throw error;
            } finally {
                clearTimeout(deadline);
                options.signal.removeEventListener("abort", abort);
            }
        }
        const response = await doFetch(url.href, config);
        if (!response.ok) {
            if (response.body) void response.body.cancel().catch(() => {});
            return response;
        }

        // Error responses, unexpected payloads and redirects must never be measured as speed.
        // Successful upload acknowledges the posted bytes with an empty response.
        if (!response.body) {
            if (isDown && bytes !== 0) throw new Error("Missing download body");
            return response;
        }
        const reader = response.body.getReader();
        let received = 0;
        const body = new ReadableStream({
            async pull(controller) {
                try {
                    const { done, value } = await reader.read();
                    if (done) {
                        if (isDown && received !== bytes) throw new Error("Short download");
                        controller.close();
                        return;
                    }
                    received += value.byteLength;
                    if (received > (isDown ? bytes : 1024)) {
                        void reader.cancel().catch(() => {});
                        throw new Error("Oversized measurement response");
                    }
                    controller.enqueue(value);
                } catch (error) {
                    controller.error(error);
                }
            },
            cancel(reason) { return reader.cancel(reason); }
        }, { highWaterMark: 0 });
        return new Response(body, {
            headers: response.headers, status: response.status, statusText: response.statusText
        });
    };

    const rounds = type => [
        { type, bytes: 256 * 1024, count: 1 },
        { type, bytes: MiB, count: 1 },
        { type, bytes: 4 * MiB, count: 3 }
    ];
    const values = results => {
        const summary = results.getSummary();
        return {
            ping: results.getUnloadedLatencyPoints().length ? summary.latency : null,
            down: summary.download ?? null,
            up: summary.upload ?? null
        };
    };

    try {
        engine = new SpeedTest({
            autoStart: false,
            downloadApiUrl: origin + "/__down",
            uploadApiUrl: origin + "/__up",
            logMeasurementApiUrl: null,
            logAimApiUrl: null,
            includeCredentials: false,
            measureDownloadLoadedLatency: false,
            measureUploadLoadedLatency: false,
            abortRequestDuration: requestLimitMs,
            abortTestDuration: totalLimitMs,
            measurements: [
                { type: "latency", numPackets: 5 },
                ...rounds("download"),
                ...rounds("upload")
            ]
        });
        engine.onPhaseChange = ({ measurement }) => {
            if (!needProbe) send("stage", { stage: phase[measurement.type] });
        };
        engine.onResultsChange = () => send("metrics", values(engine.results));
        engine.onError = message => fail(
            message.includes("abortRequestDuration") || message.includes("abortTestDuration")
                ? "TIMEOUT" : "SERVER"
        );
        engine.onFinish = results => {
            if (stopped) return;
            const metrics = values(results);
            if (results.getUnloadedLatencyPoints().length !== 5
                    || !Number.isFinite(metrics.ping)
                    || !Number.isFinite(metrics.down) || metrics.down <= 0
                    || !Number.isFinite(metrics.up) || metrics.up <= 0) {
                fail("SERVER");
                return;
            }
            send("result", metrics);
            stop();
        };
        send("ready");
        engine.play();
    } catch {
        fail("ENGINE");
    }
}
