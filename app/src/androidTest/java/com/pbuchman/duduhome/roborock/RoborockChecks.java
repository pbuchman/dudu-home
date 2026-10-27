package com.pbuchman.duduhome.roborock;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.DailyCleaning;
import com.pbuchman.duduhome.automation.HomeAction;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.ProgressBus;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.ui.MainActivity;

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
public final class RoborockChecks {
    private static final String FIXTURE = "{\"schema_version\":1,\"api_base_url\":\"https://api-eu.roborock.com\","
            + "\"routine_id\":7,\"routine_name\":\"Full Cleaning\","
            + "\"auth\":{\"u\":\"example-user\",\"s\":\"example-session\",\"h\":\"example-secret\"}}";
    public static void run(Instrumentation i) throws Exception {
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
        String mopFixture = new JSONObject(FIXTURE).put("full_mop_routine_id", 8).toString();
        RoborockCredentials both = new RoborockCredentials(mopFixture);
        require(both.forAction(HomeAction.CLEANING).routine == 7 && both.forAction(HomeAction.MOP).routine == 8,
                "explicit distinct routine selection");
        require(both.forAction(HomeAction.MOP).serialized().equals(both.serialized()), "selected request preserves bundle identity");
        boolean missingMop = false;
        try { credentials.forAction(HomeAction.MOP); } catch (IllegalArgumentException expected) { missingMop = true; }
        require(missingMop, "legacy bundle never falls back to cleaning for Mop");
        for (Object bad : new Object[]{0, 7, -1, 8.5, "8", JSONObject.NULL}) {
            boolean rejected = false;
            try { new RoborockCredentials(new JSONObject(FIXTURE).put("full_mop_routine_id", bad).toString()); }
            catch (Exception expected) { rejected = true; }
            require(rejected, "reject invalid or duplicate Mop identifier");
        }
        opens[0] = 0;
        require(client.execute(both.forAction(HomeAction.MOP)) == RoborockClient.Result.ACCEPTED
                && opens[0] == 1 && captured[0].getURL().getPath().equals("/user/scene/8/execute"),
                "Mop sends one request to its own routine, not cleaning or stop");
        opens[0] = 0;
        require(new RoborockClient(url -> { opens[0]++; throw new java.io.IOException("synthetic"); }).execute(credentials)
                == RoborockClient.Result.NETWORK_UNKNOWN && opens[0] == 1, "network failure is not retried");
        require(new RoborockClient(url -> { FakeConnection f = new FakeConnection(url); f.status = 302; return f; }).execute(credentials)
                == RoborockClient.Result.SERVER_ERROR, "no redirect execution");
        require(new RoborockClient(url -> { FakeConnection f = new FakeConnection(url); f.body = "x".repeat(17000); return f; }).execute(credentials)
                == RoborockClient.Result.PROTOCOL_ERROR, "response size bound");
        automaticQuotaBoundary(i,c,credentials);
        RoborockStore store = new RoborockStore(c);
        require(store.save(FIXTURE), "encrypted save");
        require(new RoborockStore(c).read().serialized().equals(credentials.serialized()), "decrypt after store recreation");
        byte[] encrypted = Files.readAllBytes(new File(c.getNoBackupFilesDir(), "roborock.enc").toPath());
        require(!new String(encrypted, StandardCharsets.ISO_8859_1).contains("example-secret"), "no plaintext on disk");
        require(store.reject(credentials) && new RoborockStore(c).read() == null, "rejection survives recreation");
        require(store.save(FIXTURE) && !store.rejected(), "explicit replacement clears rejected state");
        require(store.save(mopFixture) && new RoborockStore(c).read().fullMopRoutine == 8,
                "Mop identifier persists encrypted across recreation");
        require(store.save(FIXTURE), "legacy bundle remains supported");
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
        require(screen.findViewById(R.id.full_mop_button).isShown(), "manual Mop tile visible");
        i.runOnMainSync(() -> ((MainActivity) screen).automaticAction(HomeAction.MOP));
        require(screen.findViewById(R.id.menu_content).isShown() && !HomeActions.busy(), "automatic Mop rejected without request");
        i.runOnMainSync(() -> screen.findViewById(R.id.full_mop_button).performClick());
        require(screen.findViewById(R.id.roborock_setup_content).isShown() && !HomeActions.busy(),
                "missing Mop opens setup without fallback request");
        require(!((MainActivity) screen).allowsExternalLaunch(), "navigation must not cover setup");
        require(!DailyCleaning.reserve(c, 101), "manual Mop leaves daily quota unchanged");
        i.runOnMainSync(() -> screen.findViewById(R.id.cancel_roborock_button).performClick());
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
        require(!((MainActivity) screen).allowsExternalLaunch(), "navigation must not cover error");
        i.runOnMainSync(() -> screen.findViewById(R.id.close_button).performClick());
        require(screen.findViewById(R.id.menu_content).getVisibility() == View.VISIBLE, "manual cleaning result returns to menu");
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.AUTH_REJECTED));
        require(screen.findViewById(R.id.roborock_setup_content).getVisibility() == View.VISIBLE, "auth result opens setup");
        i.runOnMainSync(() -> screen.findViewById(R.id.cancel_roborock_button).performClick());
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.ACCEPTED));
        android.os.SystemClock.sleep(1600); i.waitForIdleSync();
        require(screen.findViewById(R.id.call_status_content).isShown(), "success remains visible longer than old delay");
        require(!((MainActivity) screen).allowsExternalLaunch(), "navigation waits for five-second success");
        require(MainActivity.SUCCESS_DISPLAY_MS == 5000, "shared gate/routine success duration is five seconds");
        android.graphics.Bitmap preview = i.getUiAutomation().takeScreenshot();
        if (preview != null) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(c.getCacheDir(), "routine-success-preview.png"))) {
                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { preview.recycle(); }
        }
        android.os.SystemClock.sleep(3700); i.waitForIdleSync();
        require(screen.findViewById(R.id.menu_content).getVisibility() == View.VISIBLE, "success delay returns to menu");
        java.lang.reflect.Field origin = MainActivity.class.getDeclaredField("returnToMenu"); origin.setAccessible(true); origin.setBoolean(screen, false);
        i.runOnMainSync(() -> invoke(render, screen, RoborockClient.Result.ACCEPTED));
        android.os.SystemClock.sleep(5300); i.waitForIdleSync();
        require(screen.isFinishing() || screen.isDestroyed(), "background result closes activity");
        File pending = new File(c.getNoBackupFilesDir(), "pending-config.json");
        String oldNumber = new GateNumberStore(c).read();
        Files.write(pending.toPath(), "{\"schema_version\":2,\"automation_enabled\":true,\"points\":{},\"gate_number\":\"000\",\"gate_number_verified_on_current_device\":true}".getBytes(StandardCharsets.UTF_8));
        require(!PrivateImport.apply(c) && oldNumber.equals(new GateNumberStore(c).read()), "validate whole import before mutations");
        require(pending.delete(), "clear invalid fixture");
        JSONObject data = new JSONObject().put("schema_version", 2).put("automation_enabled", false)
                .put("roborock", new JSONObject(mopFixture));
        Files.write(pending.toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
        require(PrivateImport.apply(c) && !pending.exists() && store.read() != null, "one-time import removes plaintext staging");
        require(store.read().fullMopRoutine == 8, "one-time import includes manual Mop identifier");
        require(PrivateImport.apply(c) && !DailyCleaning.reserve(c, 101), "repeat launch is inert");
        new File(c.getNoBackupFilesDir(), "roborock.enc").delete();
    }
    private static long automaticAttempt(Instrumentation i, Context context, java.util.function.BooleanSupplier reservation) {
        long[] id={0};
        i.runOnMainSync(() -> {
            id[0]=ProgressBus.request(context,Kind.CLEANING);
            ProgressBus.reserveWith(id[0],reservation,ProgressBus.ReservationPolicy.SEND_ONLY);
        });
        return id[0];
    }
    private static void automaticQuotaBoundary(Instrumentation i,Context c,RoborockCredentials credentials) {
        var daily=c.getSharedPreferences("daily_cleaning",0);
        boolean had=daily.contains("last_attempt_day"); long previous=daily.getLong("last_attempt_day",Long.MIN_VALUE);
        try {
            for (Phase outcome:new Phase[]{Phase.SKIPPED,Phase.ERROR,Phase.UNKNOWN}) {
                int[] reserves={0}; long id=automaticAttempt(i,c,() -> { reserves[0]++; return true; });
                i.runOnMainSync(() -> ProgressBus.update(c,id,outcome,Reason.EXPIRED));
                require(reserves[0]==0 && !ProgressBus.claimSend(c,id),"SEND_ONLY terminal outcomes never reserve or later send");
            }
            require(daily.edit().clear().commit(),"isolated send-boundary quota");
            long cancelled=automaticAttempt(i,c,() -> DailyCleaning.reserveAtSend(c,100,() -> 100));
            i.runOnMainSync(() -> ProgressBus.cancelAttempt(ProgressBus.token(cancelled)));
            FakeConnection[] captured={null};
            require(new RoborockClient(url -> captured[0]=new FakeConnection(url)).execute(credentials,() -> ProgressBus.claimSend(c,cancelled))==RoborockClient.Result.NETWORK_UNKNOWN
                    && captured[0].writes==0 && DailyCleaning.available(c,100),"pre-send cancellation cannot reach HTTP or reserve quota");
            // Dispatch immediately before Warsaw midnight: clock changes before reservation.
            long[] clock={100}; long stale=automaticAttempt(i,c,() -> DailyCleaning.reserveAtSend(c,100,() -> clock[0]));
            ProgressBus.allowSendWith(stale,() -> true); clock[0]=101;
            new RoborockClient(url -> captured[0]=new FakeConnection(url)).execute(credentials,() -> ProgressBus.claimSend(c,stale));
            require(captured[0].writes==0 && DailyCleaning.available(c,101),"midnight inside send admission invalidates old occurrence without reserving either day");
            i.runOnMainSync(() -> ProgressBus.update(c,stale,Phase.SKIPPED,Reason.EXPIRED));
            for (boolean networkUnknown:new boolean[]{false,true}) {
                require(daily.edit().clear().commit(),"independent admitted-send fixture");
                long id=automaticAttempt(i,c,() -> DailyCleaning.reserveAtSend(c,101,() -> 101));
                RoborockClient client=new RoborockClient(url -> {
                    FakeConnection f=new FakeConnection(url); captured[0]=f;
                    f.status=500; f.failResponse=networkUnknown;
                    f.beforeWrite=() -> require(ProgressBus.sent(id) && !DailyCleaning.available(c,101),"synchronous durable quota exists before first HTTP write");
                    return f;
                });
                RoborockClient.Result result=client.execute(credentials,() -> ProgressBus.claimSend(c,id));
                require(captured[0].writes==1 && captured[0].disconnected && !DailyCleaning.available(c,101)
                        && !ProgressBus.claimSend(c,id),"admitted error/uncertainty retains quota and cannot resend");
                require(result==(networkUnknown?RoborockClient.Result.NETWORK_UNKNOWN:RoborockClient.Result.SERVER_ERROR),"fake transport covers error and unknown after sending");
                i.runOnMainSync(() -> ProgressBus.update(c,id,networkUnknown?Phase.UNKNOWN:Phase.ERROR,Reason.NONE));
                require(!DailyCleaning.reserve(new android.content.ContextWrapper(c),101),"recreated context retains sent daily protection");
            }
            // A failed disk commit mutates the cache; a later genuine attempt can recover.
            var memory=new FailedPreferences(); memory.values.put("last_attempt_day",100L); memory.fail=true;
            Context failed=new android.content.ContextWrapper(c) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                    return name.equals("daily_cleaning")?memory.prefs:super.getSharedPreferences(name,mode);
                }
            };
            long failedId=automaticAttempt(i,c,() -> DailyCleaning.reserveAtSend(failed,101,() -> 101));
            new RoborockClient(url -> captured[0]=new FakeConnection(url)).execute(credentials,() -> ProgressBus.claimSend(c,failedId));
            require(captured[0].writes==0 && memory.values.get("last_attempt_day").equals(100L),"failed persistence blocks HTTP and restores prior cached protection");
            i.runOnMainSync(() -> ProgressBus.update(c,failedId,Phase.ERROR,Reason.DAILY_LIMIT_OR_STORAGE));
            memory.fail=false;
            long fresh=automaticAttempt(i,c,() -> DailyCleaning.reserveAtSend(failed,101,() -> 101));
            require(new RoborockClient(url -> captured[0]=new FakeConnection(url)).execute(credentials,() -> ProgressBus.claimSend(c,fresh))==RoborockClient.Result.ACCEPTED
                    && captured[0].writes==1 && !DailyCleaning.available(failed,101),"future genuine departure may send after persistence recovers");
            i.runOnMainSync(() -> ProgressBus.update(c,fresh,Phase.SUCCEEDED,Reason.NONE));
        } finally {
            var restore=daily.edit().clear(); if(had)restore.putLong("last_attempt_day",previous);
            require(restore.commit(),"send-boundary fixture quota cleanup");
        }
    }
    private static final class FailedPreferences {
        final java.util.Map<String,Object> values=new java.util.HashMap<>(); boolean fail;
        final android.content.SharedPreferences prefs=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args) -> switch(method.getName()) {
                    case "getLong" -> values.getOrDefault(args[0],args[1]);
                    case "edit" -> editor();
                    default -> throw new AssertionError("unexpected fixture preference call");
                });
        private android.content.SharedPreferences.Editor editor() {
            java.util.Map<String,Object> edits=new java.util.HashMap<>();
            return (android.content.SharedPreferences.Editor)java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.Editor.class},(proxy,method,args) -> {
                switch(method.getName()) {
                    case "putLong" -> edits.put((String)args[0],args[1]);
                    case "remove" -> edits.put((String)args[0],null);
                    case "commit" -> { edits.forEach((key,value) -> { if(value==null)values.remove(key);else values.put(key,value); }); return !fail; }
                    default -> throw new AssertionError("unexpected fixture editor call");
                }
                return proxy;
            });
        }
    }
    private static void invoke(java.lang.reflect.Method m, Activity a, RoborockClient.Result r) {
        try { m.invoke(a, r); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static final class FakeConnection extends HttpsURLConnection {
        int status = 200, writes; boolean disconnected,failResponse; Runnable beforeWrite=() -> { }; String body = "{\"success\":true}";
        FakeConnection(URL url) { super(url); }
        int fixed() { return fixedContentLength; }
        @Override public OutputStream getOutputStream() { beforeWrite.run(); writes++; return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() throws java.io.IOException { if(failResponse)throw new java.io.IOException("synthetic after-send failure"); return status; }
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
