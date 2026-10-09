package com.pbuchman.duduhome.gate;

import com.pbuchman.duduhome.gate.syu.SyuBinderClient;
import com.pbuchman.duduhome.gate.syu.SyuModuleBinder;
import com.pbuchman.duduhome.gate.syu.SyuModuleCallback;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class GateCallCoordinator {
    public interface Listener {
        void onStateChanged(GateCallState state, String title, String description);

        void onSuccess();

        void onError(GateError error, String detail);

        /** Called after Binder cleanup, including a lifecycle close without a result. */
        default void onFinished() { }
    }

    private static final int UPDATE_DEVICE_MAPPING = 6;
    private static final int UPDATE_GLOBAL_PHONE_STATE = 9;
    private static final int UPDATE_DEVICE_PHONE_STATE = 61;
    private static final int COMMAND_DIAL = 7;
    private static final int COMMAND_HANGUP = 10;
    private static final int UPDATE_NOW = 1;
    private static final Pattern MAC_PATTERN = Pattern.compile("[0-9A-F]{12}");

    private static final AtomicBoolean ACTIVE_SESSION = new AtomicBoolean(false);
    private static final AtomicLong NEXT_ATTEMPT_ID = new AtomicLong(0L);

    private final HandlerThread workerThread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    public interface DialAdmission { boolean admit(java.util.function.BooleanSupplier reservation); }
    private final DialAdmission dialAdmission;
    private final String gateNumber;
    private final GateNumberStore gateNumberStore;
    private final long attemptId = NEXT_ATTEMPT_ID.incrementAndGet();
    private final AtomicBoolean closePosted = new AtomicBoolean(false);
    private final Map<String, String> deviceMacs = new LinkedHashMap<>();
    private final Map<String, PhoneState> deviceStates = new LinkedHashMap<>();
    private final Set<Integer> registeredUpdates = new LinkedHashSet<>();

    private final Runnable snapshotTimeout = this::onSnapshotTimeout;
    private final Runnable dialTimeout = this::onDialTimeout;
    private final Runnable delayedHangup = this::onHangupDelayElapsed;
    private final Runnable hangupTimeout = this::onHangupTimeout;
    private final Runnable totalTimeout = this::onTotalTimeout;

    private SyuBinderClient binderClient;
    private SyuModuleBinder module;
    private SyuModuleCallback moduleCallback;
    private GateCallState state = GateCallState.STARTING;
    private PhoneState globalPhoneState;
    private Target target;
    private boolean lockHeld;
    private boolean terminal;
    private boolean multiDeviceDataSeen;
    private boolean dialDispatchStarted;
    private boolean outgoingObserved;
    private boolean finalIdleObserved;
    private boolean hangupSent;
    private boolean hangupProhibited;

    public GateCallCoordinator(
            Context context,
            String gateNumber,
            GateNumberStore gateNumberStore,
            Listener listener) {
        this(context, gateNumber, gateNumberStore, listener, java.util.function.BooleanSupplier::getAsBoolean);
    }
    public GateCallCoordinator(Context context, String gateNumber, GateNumberStore gateNumberStore,
                               Listener listener, DialAdmission dialAdmission) {
        this.dialAdmission = dialAdmission;
        String normalizedGateNumber = GateNumberStore.normalize(gateNumber);
        if (normalizedGateNumber == null || !normalizedGateNumber.equals(gateNumber)) {
            throw new IllegalArgumentException("Gate number must be normalized");
        }
        this.gateNumber = normalizedGateNumber;
        this.gateNumberStore = gateNumberStore;
        this.listener = listener;
        workerThread = new HandlerThread("DuduGateSession-" + attemptId);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        binderClient = new SyuBinderClient(
                context,
                worker,
                new SyuBinderClient.Listener() {
                    @Override
                    public void onModuleReady(SyuModuleBinder readyModule, String path) {
                        handleModuleReady(readyModule, path);
                    }

                    @Override
                    public void onUnavailable(String detail) {
                        finishError(GateError.BT_SERVICE_UNAVAILABLE, detail);
                    }

                    @Override
                    public void onConnectionLost(String detail) {
                        finishError(GateError.BT_CONNECTION_LOST, detail);
                    }
                });
    }

    public void start() {
        worker.post(this::startInternal);
    }

    public void close() {
        if (closePosted.compareAndSet(false, true)) {
            worker.post(this::closeInternal);
        }
    }

    private void startInternal() {
        if (!ACTIVE_SESSION.compareAndSet(false, true)) {
            finishError(GateError.ATTEMPT_IN_PROGRESS,
                    "Procesowa blokada odrzuciła równoległą próbę.");
            return;
        }
        lockHeld = true;

        Log.i(SyuBinderClient.TAG, "Attempt " + attemptId + " started");
        transition(
                GateCallState.STARTING,
                "Sprawdzam telefon",
                "Przygotowuję bezpieczną próbę.");
        worker.postDelayed(totalTimeout, GateConfig.TOTAL_ATTEMPT_TIMEOUT_MS);
        transition(
                GateCallState.BINDING,
                "Sprawdzam telefon",
                "Łączę z modułem Bluetooth radia.");
        binderClient.connect();
    }

    private void handleModuleReady(SyuModuleBinder readyModule, String path) {
        if (terminal) {
            return;
        }
        module = readyModule;
        moduleCallback = new SyuModuleCallback(worker, this::handleModuleUpdate);
        transition(
                GateCallState.CHECKING_PHONE,
                "Sprawdzam telefon",
                "Odczytuję stan połączonych urządzeń.");

        try {
            registerUpdate(UPDATE_DEVICE_MAPPING);
            registerUpdate(UPDATE_DEVICE_PHONE_STATE);
            registerUpdate(UPDATE_GLOBAL_PHONE_STATE);
            Log.i(SyuBinderClient.TAG, "Callbacks registered before dial via " + path);
            worker.postDelayed(snapshotTimeout, GateConfig.BIND_TIMEOUT_MS);
        } catch (RemoteException | RuntimeException exception) {
            Log.e(SyuBinderClient.TAG, "Callback registration failed", exception);
            finishError(GateError.IPC_ERROR,
                    "Nie można zarejestrować callbacków stanu: "
                            + exception.getClass().getSimpleName());
        }
    }

    private void registerUpdate(int updateCode) throws RemoteException {
        module.register(moduleCallback, updateCode, UPDATE_NOW);
        registeredUpdates.add(updateCode);
    }

    private void handleModuleUpdate(
            int updateCode,
            int[] ints,
            float[] floats,
            String[] strings) {
        if (terminal) {
            return;
        }

        String rawState = ints != null && ints.length > 0 ? String.valueOf(ints[0]) : "none";
        Log.d(
                SyuBinderClient.TAG,
                "Callback update="
                        + updateCode
                        + " state="
                        + rawState
                        + " payload="
                        + describePayloadShape(ints, floats, strings));

        switch (updateCode) {
            case UPDATE_DEVICE_MAPPING -> handleDeviceMapping(strings);
            case UPDATE_GLOBAL_PHONE_STATE -> handleGlobalState(ints);
            case UPDATE_DEVICE_PHONE_STATE -> handleDeviceState(ints, strings);
            default -> Log.w(SyuBinderClient.TAG,
                    "Ignoring unregistered callback code " + updateCode);
        }

    }

    private void handleDeviceMapping(String[] strings) {
        multiDeviceDataSeen = true;
        if (strings == null || strings.length == 0) {
            Log.w(SyuBinderClient.TAG, "Malformed device mapping callback");
            return;
        }

        boolean macList = true;
        for (String value : strings) {
            if (normalizeMac(value) == null) {
                macList = false;
                break;
            }
        }
        if (macList) {
            for (String value : strings) {
                String normalizedMac = normalizeMac(value);
                deviceMacs.put(normalizedMac, normalizedMac);
            }
            Log.d(SyuBinderClient.TAG,
                    "Mapped " + strings.length + " masked MAC entr"
                            + (strings.length == 1 ? "y" : "ies") + " from callback 6");
            return;
        }

        if (strings.length < 2 || isBlank(strings[0])) {
            Log.w(SyuBinderClient.TAG, "Malformed device mapping callback");
            return;
        }

        String normalizedMac = normalizeMac(strings[1]);
        if (normalizedMac == null) {
            Log.w(SyuBinderClient.TAG,
                    "Invalid MAC mapping for device " + maskIdentifier(strings[0]));
            return;
        }
        deviceMacs.put(strings[0], normalizedMac);
        Log.d(SyuBinderClient.TAG,
                "Mapped device " + maskIdentifier(strings[0]) + " to a masked MAC");
    }

    private void handleGlobalState(int[] ints) {
        if (ints == null || ints.length == 0) {
            finishError(GateError.IPC_ERROR, "Callback 9 nie zawiera kodu stanu.");
            return;
        }

        PhoneState observed = PhoneState.fromGlobalCode(ints[0]);
        if (observed == PhoneState.UNKNOWN) {
            finishError(GateError.IPC_ERROR,
                    "Nieznany globalny kod stanu telefonu: " + ints[0] + ".");
            return;
        }
        globalPhoneState = observed;

        if (dialDispatchStarted && state != GateCallState.CHECKING_PHONE) {
            if (target != null && target.usesMac() && target.deviceId() != null
                    && deviceStates.containsKey(target.deviceId())
                    && shouldWaitForSelectedDeviceCallback(observed)) {
                Log.d(SyuBinderClient.TAG,
                        "Waiting for selected-device callback instead of global call progress");
                return;
            }
            processSessionPhoneState(observed);
        }
    }

    private void handleDeviceState(int[] ints, String[] strings) {
        multiDeviceDataSeen = true;
        if (ints == null || ints.length == 0 || strings == null || strings.length == 0
                || isBlank(strings[0])) {
            finishError(GateError.IPC_ERROR, "Callback 61 ma niepełne dane.");
            return;
        }

        String deviceId = strings[0];
        String normalizedDeviceMac = normalizeMac(deviceId);
        if (normalizedDeviceMac != null && deviceMacs.containsKey(normalizedDeviceMac)) {
            deviceId = normalizedDeviceMac;
        }
        PhoneState observed = PhoneState.fromDeviceCode(ints[0]);
        if (observed == PhoneState.UNKNOWN) {
            finishError(GateError.IPC_ERROR,
                    "Nieznany kod stanu urządzenia: " + ints[0] + ".");
            return;
        }
        deviceStates.put(deviceId, observed);

        if (!dialDispatchStarted || target == null || !target.usesMac()) {
            return;
        }

        if (deviceId.equals(target.deviceId())) {
            processSessionPhoneState(observed);
            return;
        }

        if (observed.isBusy()) {
            Log.e(SyuBinderClient.TAG,
                    "Another device became busy during attempt: " + maskIdentifier(deviceId));
            bestEffortHangupOnce();
            finishError(GateError.PHONE_BUSY,
                    "Inny telefon zmienił stan rozmowy podczas próby.");
        }
    }

    private void onSnapshotTimeout() {
        if (terminal || state != GateCallState.CHECKING_PHONE) {
            return;
        }
        if (globalPhoneState == null) {
            finishError(GateError.IPC_ERROR,
                    "Brak odpowiedzi callbacku 9 z aktualnym stanem telefonu.");
            return;
        }
        evaluateInitialSnapshot();
    }

    private void evaluateInitialSnapshot() {
        if (terminal || state != GateCallState.CHECKING_PHONE) {
            return;
        }
        worker.removeCallbacks(snapshotTimeout);

        if (globalPhoneState == null) {
            finishError(GateError.IPC_ERROR, "Stan telefonu nie został odczytany.");
            return;
        }

        if (globalPhoneState == PhoneState.DISCONNECTED) {
            finishError(GateError.PHONE_NOT_CONNECTED, "Stan 0: telefon jest odłączony.");
            return;
        }
        if (globalPhoneState == PhoneState.CONNECTING) {
            finishError(GateError.PHONE_NOT_CONNECTED, "Stan 1: trwa łączenie telefonu.");
            return;
        }
        if (globalPhoneState == PhoneState.PAIRING) {
            finishError(GateError.PHONE_NOT_CONNECTED, "Stan 6: trwa parowanie telefonu.");
            return;
        }
        if (globalPhoneState.isBusy()) {
            finishError(GateError.PHONE_BUSY,
                    "Przed uruchomieniem dial wykryto stan " + globalPhoneState + ".");
            return;
        }
        if (globalPhoneState != PhoneState.IDLE) {
            finishError(GateError.IPC_ERROR,
                    "Nieobsługiwany stan początkowy: " + globalPhoneState + ".");
            return;
        }

        for (PhoneState deviceState : deviceStates.values()) {
            if (deviceState.isBusy()) {
                finishError(GateError.PHONE_BUSY,
                        "Co najmniej jeden telefon ma aktywny stan rozmowy.");
                return;
            }
            if (deviceState == PhoneState.CONNECTING) {
                finishError(GateError.PHONE_NOT_CONNECTED,
                        "Co najmniej jeden telefon nadal się łączy.");
                return;
            }
        }

        target = chooseTarget();
        if (target == null || terminal) {
            return;
        }

        Log.i(SyuBinderClient.TAG,
                "Selected phone=" + target.logIdentifier()
                        + " variant=" + (target.usesMac() ? "DUDU7_MAC" : "SINGLE_DEVICE"));
        transition(
                GateCallState.READY,
                "Telefon połączony",
                "Telefon jest gotowy i nie prowadzi rozmowy.");
        requestDial();
    }

    private Target chooseTarget() {
        if (!deviceStates.isEmpty()) {
            List<String> idleDevices = new ArrayList<>();
            for (Map.Entry<String, PhoneState> entry : deviceStates.entrySet()) {
                if (entry.getValue() == PhoneState.IDLE) {
                    idleDevices.add(entry.getKey());
                }
            }

            if (idleDevices.size() > 1) {
                finishError(GateError.MULTIPLE_PHONES,
                        "Callback 61 wskazuje więcej niż jeden telefon idle.");
                return null;
            }
            if (idleDevices.size() == 1) {
                String deviceId = idleDevices.get(0);
                for (String mappedDeviceId : deviceMacs.keySet()) {
                    if (!mappedDeviceId.equals(deviceId)
                            && !deviceStates.containsKey(mappedDeviceId)) {
                        finishError(GateError.MULTIPLE_PHONES,
                                "Dodatkowe mapowanie telefonu nie ma jednoznacznego stanu.");
                        return null;
                    }
                }
                String mac = deviceMacs.get(deviceId);
                if (mac == null) {
                    finishError(GateError.IPC_ERROR,
                            "Brak poprawnego MAC dla jednoznacznego deviceId.");
                    return null;
                }
                if (deviceId.equals(mac)) {
                    Log.i(SyuBinderClient.TAG,
                            "Using single-device commands for the callback-6 MAC-list format");
                    return Target.singleDevice();
                }
                return Target.withMac(deviceId, mac);
            }

            finishError(GateError.IPC_ERROR,
                    "Globalny stan idle nie zgadza się ze stanami urządzeń.");
            return null;
        }

        if (deviceMacs.size() > 1) {
            finishError(GateError.MULTIPLE_PHONES,
                    "Odebrano więcej niż jedno mapowanie telefonu bez stanów per-device.");
            return null;
        }
        if (deviceMacs.size() == 1) {
            Map.Entry<String, String> mapping = deviceMacs.entrySet().iterator().next();
            if (mapping.getKey().equals(mapping.getValue())) {
                Log.i(SyuBinderClient.TAG,
                        "Using single-device commands for the only callback-6 MAC");
                return Target.singleDevice();
            }
            finishError(GateError.IPC_ERROR,
                    "Mapowanie urządzenia nie zawiera stanu per-device.");
            return null;
        }
        if (multiDeviceDataSeen) {
            finishError(GateError.IPC_ERROR,
                    "Dane wielourządzeniowe są niepełne, wybór telefonu byłby ryzykowny.");
            return null;
        }
        return Target.singleDevice();
    }

    private void requestDial() {
        if (terminal || module == null || target == null || globalPhoneState != PhoneState.IDLE) {
            finishError(GateError.IPC_ERROR,
                    "Stan telefonu zmienił się przed wysłaniem dial.");
            return;
        }
        if (target.usesMac() && deviceStates.containsKey(target.deviceId())
                && deviceStates.get(target.deviceId()) != PhoneState.IDLE) {
            finishError(GateError.PHONE_BUSY,
                    "Wybrany telefon przestał być idle przed dial.");
            return;
        }

        java.util.concurrent.atomic.AtomicReference<GateNumberStore.DialReservation> reserved = new java.util.concurrent.atomic.AtomicReference<>();
        boolean admitted = dialAdmission.admit(() -> {
            reserved.set(gateNumberStore.reserveDial());
            return reserved.get() == GateNumberStore.DialReservation.RESERVED;
        });
        GateNumberStore.DialReservation reservation = reserved.get();
        if (!admitted && reservation == null) {
            finishError(GateError.IPC_ERROR, "Próba została anulowana albo zapis blokady działania nie powiódł się."); return;
        }
        if (reservation == GateNumberStore.DialReservation.COOLDOWN) {
            finishError(GateError.RETRY_TOO_SOON,
                    "Trwa blokada czasowa po poprzednim poleceniu dial.");
            return;
        }
        if (reservation == GateNumberStore.DialReservation.STORAGE_ERROR) {
            finishError(GateError.IPC_ERROR,
                    "Nie można trwale zapisać blokady kolejnej próby.");
            return;
        }

        transition(
                GateCallState.DIAL_REQUESTED,
                "Łączę z bramą",
                "Zlecam połączenie przez kartę SIM telefonu.");

        String[] strings = target.usesMac()
                ? new String[]{target.mac(), gateNumber}
                : new String[]{gateNumber};

        dialDispatchStarted = true;
        Log.i(SyuBinderClient.TAG,
                "Sending dial command 7 to " + target.logIdentifier()
                        + " number=" + maskedGateNumber());
        try {
            module.command(COMMAND_DIAL, null, null, strings);
        } catch (RemoteException | RuntimeException exception) {
            Log.e(SyuBinderClient.TAG, "Dial transaction failed", exception);
            bestEffortHangupOnce();
            finishError(GateError.IPC_ERROR,
                    "Transakcja dial nie powiodła się: "
                            + exception.getClass().getSimpleName());
            return;
        }

        worker.postDelayed(dialTimeout, GateConfig.DIAL_START_TIMEOUT_MS);
    }

    private void processSessionPhoneState(PhoneState observed) {
        if (terminal || !dialDispatchStarted) {
            return;
        }

        switch (observed) {
            case OUTGOING -> observeOutgoing();
            case IDLE -> {
                if (outgoingObserved) {
                    finalIdleObserved = true;
                    finishSuccess();
                }
            }
            case ACTIVE -> {
                if (outgoingObserved) {
                    requestHangupAndWait();
                } else {
                    bestEffortHangupOnce();
                    finishError(GateError.DIAL_TIMEOUT,
                            "Stan aktywny pojawił się bez wymaganego stanu wychodzącego.");
                }
            }
            case DISCONNECTED, CONNECTING, PAIRING -> {
                bestEffortHangupOnce();
                finishError(GateError.BT_CONNECTION_LOST,
                        "Telefon utracił gotowość podczas próby: " + observed + ".");
            }
            case INCOMING -> handleUnexpectedCallCollision("połączenie przychodzące");
            case COMPLEX -> handleUnexpectedCallCollision("stan wielu rozmów");
            case UNKNOWN -> {
                bestEffortHangupOnce();
                finishError(GateError.IPC_ERROR, "Odebrano nieznany stan telefonu.");
            }
        }
    }

    private void observeOutgoing() {
        if (outgoingObserved || terminal) {
            return;
        }
        outgoingObserved = true;
        worker.removeCallbacks(dialTimeout);
        transition(
                GateCallState.OUTGOING,
                "Wysyłam sygnał",
                "Wykryto połączenie wychodzące z tej próby.");
        transition(
                GateCallState.WAITING_BEFORE_HANGUP,
                "Wysyłam sygnał",
                "Czekam przed bezpiecznym rozłączeniem.");
        worker.postDelayed(delayedHangup, GateConfig.HANGUP_DELAY_MS);
    }

    private void handleUnexpectedCallCollision(String description) {
        if (target != null && target.usesMac()) {
            bestEffortHangupOnce();
        } else {
            hangupProhibited = true;
            Log.w(SyuBinderClient.TAG,
                    "Skipping ambiguous single-device hangup after " + description);
        }
        finishError(GateError.PHONE_BUSY,
                "Podczas próby pojawiło się " + description + ".");
    }

    private void onDialTimeout() {
        if (terminal || outgoingObserved || !dialDispatchStarted) {
            return;
        }
        Log.e(SyuBinderClient.TAG, "Dial start timeout");
        bestEffortHangupOnce();
        finishError(GateError.DIAL_TIMEOUT,
                "Brak stanu wychodzącego w ciągu "
                        + GateConfig.DIAL_START_TIMEOUT_MS + " ms.");
    }

    private void onHangupDelayElapsed() {
        if (terminal || !outgoingObserved || hangupSent) {
            return;
        }
        requestHangupAndWait();
    }

    private void requestHangupAndWait() {
        if (terminal || hangupSent) {
            return;
        }
        transition(
                GateCallState.HANGING_UP,
                "Kończę połączenie",
                "Czekam na potwierdzenie stanu gotowości.");
        if (!sendHangupOnce()) {
            finishError(GateError.IPC_ERROR,
                    "Polecenie rozłączenia nie zostało przyjęte przez IPC.");
            return;
        }
        worker.postDelayed(hangupTimeout, GateConfig.HANGUP_CONFIRM_TIMEOUT_MS);
    }

    private boolean sendHangupOnce() {
        if (!dialDispatchStarted || hangupSent || target == null || module == null
                || hangupProhibited) {
            return false;
        }
        hangupSent = true;
        String[] strings = target.usesMac() ? new String[]{target.mac()} : null;
        Log.i(SyuBinderClient.TAG,
                "Sending hangup command 10 to " + target.logIdentifier());
        try {
            module.command(COMMAND_HANGUP, null, null, strings);
            return true;
        } catch (RemoteException | RuntimeException exception) {
            Log.e(SyuBinderClient.TAG, "Hangup transaction failed", exception);
            return false;
        }
    }

    private void bestEffortHangupOnce() {
        if (!dialDispatchStarted || hangupSent || hangupProhibited) {
            return;
        }
        Log.w(SyuBinderClient.TAG, "Executing one best-effort hangup");
        sendHangupOnce();
    }

    private void onHangupTimeout() {
        if (terminal || finalIdleObserved) {
            return;
        }
        Log.e(SyuBinderClient.TAG, "Hangup confirmation timeout");
        finishError(GateError.HANGUP_TIMEOUT,
                "Brak powrotu do idle w ciągu "
                        + GateConfig.HANGUP_CONFIRM_TIMEOUT_MS + " ms.");
    }

    private void onTotalTimeout() {
        if (terminal) {
            return;
        }
        Log.e(SyuBinderClient.TAG, "Total attempt timeout");
        bestEffortHangupOnce();
        finishError(GateError.ATTEMPT_TIMEOUT,
                "Cała próba przekroczyła " + GateConfig.TOTAL_ATTEMPT_TIMEOUT_MS + " ms.");
    }

    private void finishSuccess() {
        if (terminal) {
            return;
        }
        if (!dialDispatchStarted || !outgoingObserved || !finalIdleObserved) {
            bestEffortHangupOnce();
            finishError(GateError.IPC_ERROR,
                    "Odrzucono sukces bez sekwencji dial, outgoing, idle.");
            return;
        }

        transition(
                GateCallState.SUCCESS,
                "Sygnał wysłany",
                "Telefon wrócił do stanu gotowości.");
        terminal = true;
        Log.i(SyuBinderClient.TAG, "Attempt " + attemptId + " succeeded");
        cleanup();
        main.post(listener::onSuccess);
        workerThread.quitSafely();
    }

    private void finishError(GateError error, String detail) {
        if (terminal) {
            return;
        }
        bestEffortHangupOnce();
        transition(
                GateCallState.ERROR,
                "Nie udało się wysłać sygnału",
                error.message());
        terminal = true;
        Log.e(SyuBinderClient.TAG,
                "Attempt " + attemptId + " failed: " + error.code() + " " + detail);
        cleanup();
        main.post(() -> listener.onError(error, detail));
        workerThread.quitSafely();
    }

    private void closeInternal() {
        if (terminal) {
            return;
        }
        if (dialDispatchStarted) {
            bestEffortHangupOnce();
        }
        terminal = true;
        Log.i(SyuBinderClient.TAG, "Attempt " + attemptId + " closed by lifecycle");
        cleanup();
        workerThread.quitSafely();
    }

    private void cleanup() {
        worker.removeCallbacks(snapshotTimeout);
        worker.removeCallbacks(dialTimeout);
        worker.removeCallbacks(delayedHangup);
        worker.removeCallbacks(hangupTimeout);
        worker.removeCallbacks(totalTimeout);

        if (moduleCallback != null) {
            moduleCallback.deactivate();
        }
        if (module != null && moduleCallback != null) {
            for (Integer updateCode : registeredUpdates) {
                try {
                    module.unregister(moduleCallback, updateCode);
                } catch (RemoteException | RuntimeException exception) {
                    Log.w(SyuBinderClient.TAG,
                            "Callback unregister failed for " + updateCode + ": "
                                    + exception.getClass().getSimpleName());
                }
            }
        }
        registeredUpdates.clear();

        if (binderClient != null) {
            binderClient.close();
        }
        if (lockHeld) {
            ACTIVE_SESSION.set(false);
            lockHeld = false;
        }
        main.post(listener::onFinished);
    }

    private void transition(GateCallState newState, String title, String description) {
        state = newState;
        Log.i(SyuBinderClient.TAG, "State -> " + newState);
        main.post(() -> listener.onStateChanged(newState, title, description));
    }

    private static boolean shouldWaitForSelectedDeviceCallback(PhoneState phoneState) {
        return phoneState == PhoneState.IDLE
                || phoneState == PhoneState.OUTGOING;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String describePayloadShape(int[] ints, float[] floats, String[] strings) {
        StringBuilder description =
                new StringBuilder()
                        .append("ints=")
                        .append(ints == null ? "null" : ints.length)
                        .append(",floats=")
                        .append(floats == null ? "null" : floats.length)
                        .append(",strings=")
                        .append(strings == null ? "null" : strings.length);
        if (strings == null || strings.length == 0) {
            return description.toString();
        }

        description.append('[');
        for (int index = 0; index < strings.length; index++) {
            if (index > 0) {
                description.append(';');
            }
            String value = strings[index];
            if (value == null) {
                description.append("null");
            } else if (value.trim().isEmpty()) {
                description.append("blank");
            } else {
                description
                        .append("len=")
                        .append(value.length())
                        .append(",mac=")
                        .append(normalizeMac(value) != null);
            }
        }
        return description.append(']').toString();
    }

    private static String normalizeMac(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace(":", "")
                .replace("-", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        return MAC_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private static String maskIdentifier(String deviceId) {
        if (deviceId == null) {
            return "unknown";
        }
        return "id#" + Integer.toHexString(deviceId.hashCode());
    }

    private String maskedGateNumber() {
        return "[private]";
    }

    private record Target(String deviceId, String mac, boolean usesMac) {
        static Target withMac(String deviceId, String mac) {
            return new Target(deviceId, mac, true);
        }

        static Target singleDevice() {
            return new Target(null, null, false);
        }

        String logIdentifier() {
            return usesMac ? maskIdentifier(deviceId) : "single-active-device";
        }
    }
}
