package com.pbuchman.duduhome.speed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs only bundled code under a virtual HTTPS origin. All UI and lifecycle are native. */
public final class SpeedTestWebEngine {
    private static final String ORIGIN = "https://speed-test.dudu.invalid";
    private static final String INDEX = ORIGIN + "/index.html";
    public static final String CLOUDFLARE = "https://speed.cloudflare.com";
    private static final Map<String, String> ASSETS = Map.of(
            "/index.html", "text/html",
            "/measurement.js", "text/javascript",
            "/cloudflare-speedtest-1.17.0.js", "text/javascript");

    public interface Listener {
        void message(JSONObject message);
        void failed();
        void networkChanged();
    }

    private final WebView browser;
    private final ViewGroup parent;
    private final Handler main;
    private final Listener listener;
    private final String endpoint;
    private final String authority;
    private final boolean probeFirst;
    private final BooleanSupplier onExpectedNetwork;
    private WebMessagePort port;
    private volatile boolean closed;

    @SuppressLint("SetJavaScriptEnabled")
    public SpeedTestWebEngine(Context context, ViewGroup parent, Handler main, int emulatorPort,
                              boolean probeFirst, BooleanSupplier onExpectedNetwork, Listener listener) {
        this.parent = parent;
        this.main = main;
        this.listener = listener;
        this.onExpectedNetwork = onExpectedNetwork;
        this.probeFirst = probeFirst;
        endpoint = emulatorPort == 0 ? CLOUDFLARE : "https://127.0.0.1:" + emulatorPort;
        authority = Uri.parse(endpoint).getEncodedAuthority();

        browser = new WebView(context);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        browser.setImportantForAccessibility(WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        browser.setFocusable(false);
        browser.setAlpha(0f);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                // The upstream engine logs raw results and URLs to its console by default.
                return true;
            }
        });
        browser.setWebViewClient(new LocalPageClient(context.getApplicationContext()));
        parent.addView(browser, new ViewGroup.LayoutParams(1, 1));
    }

    public void start() {
        browser.loadUrl(INDEX);
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (port != null) {
            try {
                port.postMessage(new WebMessage("stop"));
                port.close();
            } catch (IllegalStateException ignored) {
                // A terminated renderer may already have closed the channel.
            }
        }
        browser.stopLoading();
        parent.removeView(browser);
        browser.destroy();
    }

    private final class LocalPageClient extends WebViewClient {
        private final Context context;

        private LocalPageClient(Context context) {
            this.context = context;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (closed || !"https".equals(url.getScheme())) return blocked();
            if ("speed-test.dudu.invalid".equals(url.getEncodedAuthority()) && "GET".equals(request.getMethod())
                    && url.getEncodedQuery() == null && url.getFragment() == null) {
                String path = url.getEncodedPath();
                String type = ASSETS.get(path);
                if (type == null) return blocked();
                try {
                    String csp = "default-src 'none'; script-src 'self'; connect-src "
                            + endpoint + "/__down " + endpoint + "/__up; "
                            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
                    return new WebResourceResponse(type, "UTF-8", 200, "OK", Map.of(
                            "Content-Security-Policy", csp,
                            "X-Content-Type-Options", "nosniff",
                            "Referrer-Policy", "no-referrer",
                            "Cache-Control", "no-store"),
                            context.getAssets().open("speed" + path));
                } catch (IOException unavailable) {
                    main.post(listener::failed);
                    return blocked();
                }
            }

            String method = request.getMethod();
            String path = url.getEncodedPath();
            boolean download = "/__down".equals(path) && ("GET".equals(method) || "OPTIONS".equals(method));
            boolean upload = "/__up".equals(path) && ("POST".equals(method) || "OPTIONS".equals(method));
            if (!request.isForMainFrame() && authority.equals(url.getEncodedAuthority()) && (download || upload)) {
                if (onExpectedNetwork.getAsBoolean()) return null;
                main.post(listener::networkChanged);
            }
            return blocked();
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (closed || port != null || !INDEX.equals(url)) return;
            try {
                WebMessagePort[] channel = view.createWebMessageChannel();
                port = channel[0];
                port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort source, WebMessage message) {
                        if (closed) return;
                        String data = message.getData();
                        if (data == null || data.length() > 512) {
                            listener.failed();
                            return;
                        }
                        try {
                            listener.message(new JSONObject(data));
                        } catch (JSONException invalid) {
                            listener.failed();
                        }
                    }
                }, main);
                view.postWebMessage(new WebMessage(endpoint + "\n" + probeFirst, new WebMessagePort[]{channel[1]}), Uri.parse(ORIGIN));
            } catch (RuntimeException unavailable) {
                listener.failed();
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!closed && request.isForMainFrame()) listener.failed();
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail details) {
            if (!closed) listener.failed();
            return true;
        }
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Map.of(),
                new ByteArrayInputStream(new byte[0]));
    }
}
