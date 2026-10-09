package com.pbuchman.duduhome.roborock;



import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** One POST, no redirects/retry and no robot stop/status commands. */
public final class RoborockClient {
    public enum Result { ACCEPTED, AUTH_REJECTED, NETWORK_UNKNOWN, SERVER_ERROR, PROTOCOL_ERROR }
    private volatile HttpsURLConnection connection;
    private volatile boolean cancelled;
    public interface Connections { HttpsURLConnection open(URL url) throws Exception; }
    private final Connections connections;
    public RoborockClient() { this(url -> (HttpsURLConnection) url.openConnection()); }
    public RoborockClient(Connections connections) { this.connections = connections; }
    public static String hawk(RoborockCredentials c, String path, long seconds, String nonce) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(path.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String pre = c.user + ":" + c.session + ":" + nonce + ":" + seconds + ":" + hex + "::";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(c.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "Hawk id=\"" + c.user + "\",s=\"" + c.session + "\",ts=\"" + seconds
                + "\",nonce=\"" + nonce + "\",mac=\"" + Base64.getEncoder().encodeToString(mac.doFinal(pre.getBytes(StandardCharsets.UTF_8))) + "\"";
    }
    public static Result classify(int status, String body) {
        try {
            JSONObject json = new JSONObject(body);
            String code = json.optString("code", "");
            String message = json.optString("msg", "");
            if (status == 401 || "auth.err.invalid.token".equals(message)
                    || "auth.err.invalid.token".equals(code)) return Result.AUTH_REJECTED;
            if (status < 200 || status >= 300) return Result.SERVER_ERROR;
            return Boolean.TRUE.equals(json.opt("success")) ? Result.ACCEPTED : Result.SERVER_ERROR;
        } catch (Exception ignored) { return status == 401 ? Result.AUTH_REJECTED : Result.PROTOCOL_ERROR; }
    }
    public Result execute(RoborockCredentials c) { return execute(c, () -> true); }
    public Result execute(RoborockCredentials c, java.util.function.BooleanSupplier admission) {
        try {
            String path = "/user/scene/" + c.routine + "/execute";
            byte[] random = new byte[6]; new SecureRandom().nextBytes(random);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            HttpsURLConnection http = connections.open(new URL(c.base + path));
            connection = http;
            if (cancelled) return Result.NETWORK_UNKNOWN;
            http.setConnectTimeout(5000); http.setReadTimeout(10000);
            http.setInstanceFollowRedirects(false); http.setRequestMethod("POST");
            http.setDoOutput(true); http.setFixedLengthStreamingMode(0);
            http.setRequestProperty("Authorization", hawk(c, path, System.currentTimeMillis() / 1000, nonce));
            http.setRequestProperty("Accept", "application/json");
            if (cancelled || !admission.getAsBoolean()) return Result.NETWORK_UNKNOWN;
            http.getOutputStream().close();
            int status = http.getResponseCode();
            java.io.InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
            if (input == null) return classify(status, "");
            try (input; java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (body.size() + n > 16384) return Result.PROTOCOL_ERROR;
                    body.write(buffer, 0, n);
                }
                return classify(status, body.toString("UTF-8"));
            }
        } catch (Exception ignored) { return Result.NETWORK_UNKNOWN; }
        finally { HttpsURLConnection c0 = connection; if (c0 != null) c0.disconnect(); }
    }
    public void cancelTransport() { cancelled = true; HttpsURLConnection c = connection; if (c != null) c.disconnect(); }
}
