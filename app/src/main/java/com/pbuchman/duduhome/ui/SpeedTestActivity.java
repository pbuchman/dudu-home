package com.pbuchman.duduhome.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.window.OnBackInvokedDispatcher;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import com.pbuchman.duduhome.speed.SpeedConnection;
import com.pbuchman.duduhome.speed.SpeedTestWebEngine;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** A foreground-only measurement. It never reserves an automation action or restarts itself. */
public final class SpeedTestActivity extends Activity {
    private static final AtomicLong IDS = new AtomicLong(SystemClock.elapsedRealtimeNanos());
    private static final long LOAD_LIMIT_MS = 10_000;
    private static final long ATTEMPT_LIMIT_MS = 45_000;
    private static volatile int emulatorPort;

    private enum Cause {
        SUCCESS, CANCELLED, PREEMPTED, NO_NETWORK, UNVALIDATED, NETWORK_CHANGED,
        SERVER, TIMEOUT, DATA_LIMIT, ENGINE
    }

    private static final class Attempt {
        final long id = IDS.incrementAndGet();
        final long deadline = SystemClock.elapsedRealtime() + ATTEMPT_LIMIT_MS;
        final SpeedConnection connection;
        SpeedTestWebEngine engine;
        volatile boolean androidValidated;
        boolean engineReady;
        int stage;

        Attempt(SpeedConnection connection) {
            this.connection = connection;
            androidValidated = connection.validated();
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager.NetworkCallback connectivity = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            refreshConnection();
        }

        @Override public void onLost(Network network) {
            Attempt current = active;
            if (current != null && network.equals(current.connection.network())) {
                finishAttempt(current, Cause.NETWORK_CHANGED);
            } else {
                refreshConnection();
            }
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            Attempt current = active;
            if (current != null && network.equals(current.connection.network())) {
                boolean validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (current.androidValidated && !validated) {
                    finishAttempt(current, Cause.UNVALIDATED);
                    return;
                }
                if (validated) current.androidValidated = true;
            }
            refreshConnection();
        }
    };
    private SpeedTestView screen;
    private ConnectivityManager manager;
    private Attempt active;
    private Cause failure;
    private boolean observing;
    private boolean started;

    /** Only debuggable Android emulators may use the in-process loopback integration fixture. */
    public static void setEmulatorEndpointForTest(int port) {
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("Invalid fixture port");
        emulatorPort = port;
    }

    private int fixturePort() {
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debug && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)) ? emulatorPort : 0;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = getSystemService(ConnectivityManager.class);
        screen = new SpeedTestView(this, this::cancelAndReturn, this::startAttempt, this::finish);
        setContentView(screen);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::cancelAndReturn);
        }
        if (state != null) finish();
    }

    @Override protected void onResume() {
        super.onResume();
        if (started || isFinishing()) return;
        started = true;
        if (manager != null) {
            try {
                manager.registerDefaultNetworkCallback(connectivity, main);
                observing = true;
            } catch (RuntimeException unavailable) {
                // No attempt may run when Android cannot report validation and route changes.
            }
        }
        startAttempt();
    }

    @Override protected void onPause() {
        if (observing) {
            manager.unregisterNetworkCallback(connectivity);
            observing = false;
        }
        if (active != null) finishAttempt(active, Cause.PREEMPTED);
        if (!isFinishing()) finish();
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // Android 13+ uses the platform OnBackInvoked callback registered above.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        cancelAndReturn();
    }

    private void cancelAndReturn() {
        if (active != null) finishAttempt(active, Cause.CANCELLED);
        finish();
    }

    private void startAttempt() {
        if (active != null || isFinishing()) return;
        SpeedConnection connection = SpeedConnection.current(this);
        Attempt attempt = new Attempt(connection);
        active = attempt;
        failure = null;
        Diagnostics.record(getApplicationContext(), "SPEED_TEST_START ID=" + attempt.id
                + " STATE=" + connection.transport().name());
        screen.connection(connection);
        screen.running();
        if (connection.network() == null) {
            finishAttempt(attempt, Cause.NO_NETWORK);
            return;
        }
        if (!observing) {
            finishAttempt(attempt, Cause.ENGINE);
            return;
        }

        try {
            var application = getApplicationContext();
            attempt.engine = new SpeedTestWebEngine(this, screen, main, fixturePort(), !connection.validated(),
                    () -> networkProblem(attempt, SpeedConnection.current(application)) == null,
                    new SpeedTestWebEngine.Listener() {
                        @Override public void message(JSONObject message) {
                            receive(attempt, message);
                        }

                        @Override public void failed() {
                            finishAttempt(attempt, Cause.ENGINE);
                        }

                        @Override public void networkChanged() {
                            Cause disconnected = networkProblem(attempt, SpeedConnection.current(application));
                            finishAttempt(attempt, disconnected == null ? Cause.NETWORK_CHANGED : disconnected);
                        }
                    });
            attempt.engine.start();
            main.postAtTime(() -> {
                if (!attempt.engineReady) finishAttempt(attempt, Cause.ENGINE);
            }, attempt, SystemClock.uptimeMillis() + LOAD_LIMIT_MS);
            main.postAtTime(() -> finishAttempt(attempt, Cause.TIMEOUT), attempt,
                    SystemClock.uptimeMillis() + Math.max(0, attempt.deadline - SystemClock.elapsedRealtime()));
        } catch (RuntimeException unavailable) {
            finishAttempt(attempt, Cause.ENGINE);
        }
    }

    private void refreshConnection() {
        if (!observing || isFinishing()) return;
        SpeedConnection connection = SpeedConnection.current(this);
        if (active != null) {
            Cause problem = networkProblem(active, connection);
            if (problem != null) finishAttempt(active, problem);
        } else if (failure != null) {
            showFailure(failure, connection);
        }
    }

    private static Cause networkProblem(Attempt attempt, SpeedConnection current) {
        SpeedConnection original = attempt.connection;
        if (!Objects.equals(original.network(), current.network()) || original.transport() != current.transport()) {
            return Cause.NETWORK_CHANGED;
        }
        if (current.validated()) {
            attempt.androidValidated = true;
        } else if (attempt.androidValidated) {
            return Cause.UNVALIDATED;
        }
        return null;
    }

    private void receive(Attempt attempt, JSONObject message) {
        if (active != attempt) return;
        Cause connectivityProblem = networkProblem(attempt, SpeedConnection.current(this));
        if (connectivityProblem != null) {
            finishAttempt(attempt, connectivityProblem);
            return;
        }
        if (SystemClock.elapsedRealtime() >= attempt.deadline) {
            finishAttempt(attempt, Cause.TIMEOUT);
            return;
        }
        switch (message.optString("event")) {
            case "ready" -> attempt.engineReady = true;
            case "stage" -> {
                int stage = switch (message.optString("stage")) {
                    case "PING" -> 1;
                    case "DOWNLOAD" -> 2;
                    case "UPLOAD" -> 3;
                    default -> 0;
                };
                if (stage > attempt.stage) {
                    attempt.stage = stage;
                    screen.stage(stage);
                    Diagnostics.record(getApplicationContext(), "SPEED_TEST_STAGE ID=" + attempt.id
                            + " STAGE=" + message.optString("stage"));
                }
            }
            case "metrics", "result" -> {
                Double down = number(message, "down", false);
                Double up = number(message, "up", false);
                Double ping = number(message, "ping", true);
                screen.metrics(down, up, ping);
                if ("result".equals(message.optString("event"))) {
                    boolean complete = down != null && up != null && ping != null && attempt.stage == 3;
                    finishAttempt(attempt, complete ? Cause.SUCCESS : Cause.SERVER);
                }
            }
            case "error" -> finishAttempt(attempt, switch (message.optString("reason")) {
                case "UNVALIDATED" -> Cause.UNVALIDATED;
                case "TIMEOUT" -> Cause.TIMEOUT;
                case "DATA_LIMIT" -> Cause.DATA_LIMIT;
                case "ENGINE" -> Cause.ENGINE;
                default -> Cause.SERVER;
            });
            default -> finishAttempt(attempt, Cause.ENGINE);
        }
    }

    private static Double number(JSONObject message, String key, boolean allowZero) {
        Object field = message.opt(key);
        if (!(field instanceof Number value)) return null;
        double number = value.doubleValue();
        return Double.isFinite(number) && number >= (allowZero ? 0 : Double.MIN_VALUE) ? number : null;
    }

    private void finishAttempt(Attempt attempt, Cause cause) {
        if (active != attempt) return;
        active = null;
        main.removeCallbacksAndMessages(attempt);
        if (attempt.engine != null) attempt.engine.close();
        Diagnostics.record(getApplicationContext(), "SPEED_TEST_END ID=" + attempt.id + " REASON=" + cause.name());
        if (cause == Cause.SUCCESS) {
            screen.result();
        } else if (cause != Cause.CANCELLED && cause != Cause.PREEMPTED) {
            failure = cause;
            showFailure(cause, SpeedConnection.current(this));
        }
    }

    private void showFailure(Cause cause, SpeedConnection current) {
        screen.connection(current);
        if (current.validated() && (cause == Cause.NO_NETWORK || cause == Cause.UNVALIDATED)) {
            screen.error(R.string.speed_network_ready, R.string.speed_network_ready_copy, true);
        } else if (cause == Cause.NETWORK_CHANGED) {
            screen.error(R.string.speed_network_changed, R.string.speed_network_changed_copy, false);
        } else if (cause == Cause.NO_NETWORK || cause == Cause.UNVALIDATED) {
            screen.error(R.string.speed_no_access, current.network() == null
                    ? R.string.speed_no_network_copy : R.string.speed_unvalidated_copy, false);
        } else if (cause == Cause.TIMEOUT) {
            screen.error(R.string.speed_timeout, R.string.speed_timeout_copy, false);
        } else if (cause == Cause.DATA_LIMIT) {
            screen.error(R.string.speed_data_limit, R.string.speed_data_limit_copy, false);
        } else if (cause == Cause.ENGINE) {
            screen.error(R.string.speed_engine_error, R.string.speed_engine_error_copy, false);
        } else {
            screen.error(R.string.speed_server_unavailable, R.string.speed_server_copy, false);
        }
    }
}
