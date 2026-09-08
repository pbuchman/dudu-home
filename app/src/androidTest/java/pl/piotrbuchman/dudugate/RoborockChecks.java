package pl.piotrbuchman.dudugate;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Synthetic fixtures only. All transport is in-memory: this never contacts a robot. */
final class RoborockChecks {
    private static final String FIXTURE = "{\"schema_version\":1,\"api_base_url\":\"https://api-eu.roborock.com\","
            + "\"routine_id\":7,\"routine_name\":\"Full Cleaning\","
            + "\"auth\":{\"u\":\"example-user\",\"s\":\"example-session\",\"h\":\"example-secret\"}}";
    static void run(Instrumentation i) throws Exception {
        Context c = i.getTargetContext();
        RoborockCredentials credentials = new RoborockCredentials(FIXTURE);
        require(RoborockClient.hawk(credentials, "/user/scene/7/execute", 100, "example-nonce").equals(
                "Hawk id=\"example-user\",s=\"example-session\",ts=\"100\",nonce=\"example-nonce\","
                + "mac=\"Xkv95RFizBVriW30A/VSQ0WkSfs0ZnF+sbGsT9GZ6Hw=\""), "Hawk matches independent Python vector");
        for (String bad : new String[]{
                FIXTURE.replace("https://", "http://"), FIXTURE.replace("api-eu.roborock.com", "example.org"),
                FIXTURE.replace("api-eu.roborock.com", "api-eu.roborock.com:443"),
                FIXTURE.replace("api-eu.roborock.com", "api-eu.roborock.com/extra"),
                FIXTURE.replace("example-user", "example\\\"user"), FIXTURE.replace("Full Cleaning", "Other"),
                FIXTURE.replace("\"routine_id\":7", "\"routine_id\":0"),
                FIXTURE.replace("\"routine_id\":7", "\"routine_id\":7.5"),
                FIXTURE.replace("\"routine_id\":7", "\"routine_id\":\"7\"")}) {
            boolean rejected = false;
            try { new RoborockCredentials(bad); } catch (Exception expected) { rejected = true; }
            require(rejected, "reject unsafe credentials");
        }
        require(RoborockClient.classify(200, "{\"success\":true}") == RoborockClient.Result.ACCEPTED, "accepted response");
        require(RoborockClient.classify(200, "{\"success\":\"true\"}") != RoborockClient.Result.ACCEPTED, "strict success boolean");
        require(RoborockClient.classify(401, "not json") == RoborockClient.Result.AUTH_REJECTED, "HTTP auth failure");
        require(RoborockClient.classify(200, "{\"msg\":\"auth.err.invalid.token\"}") == RoborockClient.Result.AUTH_REJECTED, "API auth failure");
        require(RoborockClient.classify(403, "{}") == RoborockClient.Result.SERVER_ERROR, "generic forbidden is not expiry");
        require(RoborockClient.classify(200, "broken") == RoborockClient.Result.PROTOCOL_ERROR, "invalid JSON");
        for (int status : new int[]{302, 429, 500})
            require(RoborockClient.classify(status, "{\"success\":true}") == RoborockClient.Result.SERVER_ERROR, "reject non-success HTTP");
        int[] opens = {0};
        FakeConnection[] captured = {null};
        RoborockClient client = new RoborockClient(url -> { opens[0]++; return captured[0] = new FakeConnection(url); });
        require(client.execute(credentials) == RoborockClient.Result.ACCEPTED, "fake transport success");
        FakeConnection connection = captured[0];
        require(opens[0] == 1 && connection.writes == 1, "exactly one request");
        require(connection.getURL().getPath().equals("/user/scene/7/execute"), "routine execute only");
        require(connection.getRequestMethod().equals("POST") && !connection.getInstanceFollowRedirects(), "POST without redirects");
        require(connection.fixed() == 0 && connection.getConnectTimeout() == 5000 && connection.getReadTimeout() == 10000, "bounded streaming request");
        require(connection.getRequestProperty("Authorization").startsWith("Hawk "), "signed request");
        require(connection.disconnected, "transport cleanup");
        opens[0] = 0;
        require(new RoborockClient(url -> { opens[0]++; throw new java.io.IOException("synthetic"); }).execute(credentials)
                == RoborockClient.Result.NETWORK_UNKNOWN && opens[0] == 1, "network failure is not retried");
        require(new RoborockClient(url -> { FakeConnection f = new FakeConnection(url); f.status = 302; return f; }).execute(credentials)
                == RoborockClient.Result.SERVER_ERROR, "no redirect execution");
        require(new RoborockClient(url -> { FakeConnection f = new FakeConnection(url); f.body = "x".repeat(17000); return f; }).execute(credentials)
                == RoborockClient.Result.PROTOCOL_ERROR, "response size bound");
        RoborockStore store = new RoborockStore(c);
        require(store.save(FIXTURE), "encrypted save");
        require(new RoborockStore(c).read().serialized().equals(credentials.serialized()), "decrypt after store recreation");
        byte[] encrypted = Files.readAllBytes(new File(c.getNoBackupFilesDir(), "roborock.enc").toPath());
        require(!new String(encrypted, StandardCharsets.ISO_8859_1).contains("example-secret"), "no plaintext on disk");
        require(store.reject(credentials) && new RoborockStore(c).read() == null, "rejection survives recreation");
        require(store.save(FIXTURE) && !store.rejected(), "explicit replacement clears rejected state");
        require(!store.reject(new RoborockCredentials(FIXTURE.replace("example-secret", "other-secret"))), "old request cannot reject replacement");
        require(c.getSharedPreferences("daily_cleaning", 0).edit().clear().commit(), "clear synthetic daily state");
        require(DailyCleaning.reserve(c, 100), "first automatic attempt");
        require(!DailyCleaning.reserve(c, 100) && !DailyCleaning.reserve(c, 99), "same day and rollback blocked");
        require(DailyCleaning.reserve(c, 101), "next day permitted");
        require(store.save(FIXTURE) && !DailyCleaning.reserve(c, 101), "credential save never resets quota");
        require(HomeActions.begin() && !HomeActions.begin(), "shared action exclusion"); HomeActions.end();
        // Delete only synthetic fixture data so the UI test cannot send any network request.
        require(new File(c.getNoBackupFilesDir(), "roborock.enc").delete(), "clear fixture credentials");
        Activity screen = i.startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(screen.findViewById(R.id.full_cleaning_button).getVisibility() == View.VISIBLE, "second tile visible");
        i.runOnMainSync(() -> screen.findViewById(R.id.full_cleaning_button).performClick());
        require(screen.findViewById(R.id.roborock_setup_content).getVisibility() == View.VISIBLE, "missing credentials form");
        i.runOnMainSync(() -> ((MainActivity) screen).automaticAction(HomeAction.GATE));
        require(screen.findViewById(R.id.roborock_setup_content).getVisibility() == View.VISIBLE, "automatic event cannot interrupt configuration");
        i.runOnMainSync(() -> {
            ((EditText) screen.findViewById(R.id.roborock_input)).setText(FIXTURE);
            screen.findViewById(R.id.save_roborock_button).performClick();
        });
        require(screen.findViewById(R.id.menu_content).getVisibility() == View.VISIBLE && !HomeActions.busy(), "save returns to menu without action");
        require(!DailyCleaning.reserve(c, 101), "UI save preserves quota");
        java.lang.reflect.Method numberForm = MainActivity.class.getDeclaredMethod("showNumberSetup"); numberForm.setAccessible(true);
        i.runOnMainSync(() -> {
            try { numberForm.invoke(screen); } catch (Exception e) { throw new AssertionError(e); }
        });
        require(screen.findViewById(R.id.cancel_number_button).isShown(), "number form can return to independent cleaning tile");
        i.runOnMainSync(() -> screen.findViewById(R.id.cancel_number_button).performClick());
        java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("renderCleaningResult", RoborockClient.Result.class);
        render.setAccessible(true);
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.NETWORK_UNKNOWN));
        require(screen.findViewById(R.id.error_actions).getVisibility() == View.VISIBLE, "unknown result remains actionable");
        i.runOnMainSync(() -> screen.findViewById(R.id.close_button).performClick());
        require(screen.findViewById(R.id.menu_content).getVisibility() == View.VISIBLE, "manual cleaning result returns to menu");
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.AUTH_REJECTED));
        require(screen.findViewById(R.id.roborock_setup_content).getVisibility() == View.VISIBLE, "auth result opens setup");
        i.runOnMainSync(() -> screen.findViewById(R.id.cancel_roborock_button).performClick());
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.ACCEPTED));
        android.os.SystemClock.sleep(1600); i.waitForIdleSync();
        require(screen.findViewById(R.id.menu_content).getVisibility() == View.VISIBLE, "success delay returns to menu");
        java.lang.reflect.Field origin = MainActivity.class.getDeclaredField("returnToMenu"); origin.setAccessible(true); origin.setBoolean(screen, false);
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.ACCEPTED));
        android.os.SystemClock.sleep(1600); i.waitForIdleSync();
        require(screen.isFinishing() || screen.isDestroyed(), "background result closes activity");
        File pending = new File(c.getNoBackupFilesDir(), "pending-config.json");
        String oldNumber = new GateNumberStore(c).read();
        Files.write(pending.toPath(), "{\"schema_version\":2,\"automation_enabled\":true,\"points\":{},\"gate_number\":\"000\",\"gate_number_verified_on_current_device\":true}".getBytes(StandardCharsets.UTF_8));
        require(!PrivateImport.apply(c) && oldNumber.equals(new GateNumberStore(c).read()), "validate whole import before mutations");
        require(pending.delete(), "clear invalid fixture");
        JSONObject data = new JSONObject().put("schema_version", 2).put("automation_enabled", false)
                .put("roborock", new JSONObject(FIXTURE));
        Files.write(pending.toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
        require(PrivateImport.apply(c) && !pending.exists() && store.read() != null, "one-time import removes plaintext staging");
        require(PrivateImport.apply(c) && !DailyCleaning.reserve(c, 101), "repeat launch is inert");
        new File(c.getNoBackupFilesDir(), "roborock.enc").delete();
    }
    private static void invoke(java.lang.reflect.Method m, Activity a, RoborockClient.Result r) {
        try { m.invoke(a, r); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static final class FakeConnection extends HttpsURLConnection {
        int status = 200, writes; boolean disconnected; String body = "{\"success\":true}";
        FakeConnection(URL url) { super(url); }
        int fixed() { return fixedContentLength; }
        @Override public OutputStream getOutputStream() { writes++; return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "synthetic"; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}
