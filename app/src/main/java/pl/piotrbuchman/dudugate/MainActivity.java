package pl.piotrbuchman.dudugate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static final long SUCCESS_DISPLAY_MS = 5000L;
    private static final long INFORMATION_DISPLAY_MS = 2500L;

    private ProgressBar progress;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusDescription;
    private View errorActions;
    private View numberSetupContent;
    private View callStatusContent;
    private View menuContent;
    private TextView automationStatus;
    private EditText gateNumberInput;
    private TextView gateNumberError;
    private GateNumberStore gateNumberStore;
    private String gateNumber;
    private GateCallCoordinator coordinator;
    private int uiGeneration;
    private boolean actionRunning;
    private boolean returnToMenu = true;
    private boolean resumed;
    private boolean configurationSaved = true;
    private HomeAction currentAction = HomeAction.GATE;
    private boolean gateLease;
    private View roborockSetup;
    private EditText roborockInput;
    private TextView roborockMessage;

    private final Runnable finishAfterSuccess = this::finishAction;
    private final Runnable finishAfterInformation = this::finishAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prepareActionTiles();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        progress = findViewById(R.id.progress);
        statusIcon = findViewById(R.id.status_icon);
        statusTitle = findViewById(R.id.status_title);
        statusDescription = findViewById(R.id.status_description);
        errorActions = findViewById(R.id.error_actions);
        numberSetupContent = findViewById(R.id.number_setup_content);
        callStatusContent = findViewById(R.id.call_status_content);
        menuContent = findViewById(R.id.menu_content);
        roborockSetup = findViewById(R.id.roborock_setup_content);
        roborockInput = findViewById(R.id.roborock_input);
        roborockMessage = findViewById(R.id.roborock_setup_message);
        automationStatus = findViewById(R.id.automation_status);
        gateNumberInput = findViewById(R.id.gate_number_input);
        gateNumberError = findViewById(R.id.gate_number_error);
        Button retryButton = findViewById(R.id.retry_button);
        Button closeButton = findViewById(R.id.close_button);
        Button saveNumberButton = findViewById(R.id.save_number_button);

        retryButton.setOnClickListener(view -> { returnToMenu = true; startSelected(); });
        closeButton.setOnClickListener(view -> finishAction());
        findViewById(R.id.open_gate_button).setOnClickListener(view -> {
            if (actionRunning) return;
            returnToMenu = true;
            currentAction = HomeAction.GATE;
            startAttempt();
        });
        findViewById(R.id.full_cleaning_button).setOnClickListener(view -> {
            returnToMenu = true; currentAction = HomeAction.CLEANING; startCleaning();
        });
        findViewById(R.id.full_mop_button).setOnClickListener(view -> {
            returnToMenu = true; currentAction = HomeAction.MOP; startCleaning();
        });
        findViewById(R.id.settings_button).setOnClickListener(view -> {
            if (busyNotice()) return;
            new android.app.AlertDialog.Builder(this).setTitle(R.string.settings)
                    .setItems(new String[]{"Numer bramy", "Dane dostępowe Roborock", "Lokalizacja"}, (dialog, which) -> {
                        if (which == 0) { returnToMenu = true; showNumberSetup(); }
                        else if (which == 1) showRoborockSetup(false);
                        else new android.app.AlertDialog.Builder(this).setTitle("Lokalizacja")
                                .setMessage(HomeConfiguration.load(this) == null ? "Brak konfiguracji GPS. Dostarcz prywatny plik instalatorem."
                                        : "Konfiguracja GPS zainstalowana. Zmiany przez prywatny plik instalatora.")
                                .setPositiveButton("OK", null).show();
                    }).show();
        });
        findViewById(R.id.cancel_number_button).setOnClickListener(view -> { hideKeyboard(); showMenu(); });
        findViewById(R.id.cancel_roborock_button).setOnClickListener(view -> showMenu());
        findViewById(R.id.save_roborock_button).setOnClickListener(view -> {
            if (new RoborockStore(this).save(roborockInput.getText().toString())) { showMenu(); HomeMonitorService.ensureStarted(this); }
            else roborockMessage.setText(R.string.roborock_setup_invalid);
        });
        saveNumberButton.setOnClickListener(view -> saveGateNumber());
        gateNumberInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveGateNumber();
                return true;
            }
            return false;
        });

        gateNumberStore = new GateNumberStore(this);
        configurationSaved = PrivateImport.apply(this);
        PrivateImport.migrateLegacyPhone(this);
        gateNumber = configurationSaved ? gateNumberStore.read() : null;
        HomeAction requested = HomeActions.consumeAction(getIntent());
        if (requested != null && configurationSaved) {
            currentAction = requested; returnToMenu = false; startSelected();
        } else if (gateNumber == null) {
            showNumberSetup();
        } else showMenu();
        if (!configurationSaved) automationStatus.setText(R.string.import_failed);
        HomeMonitorService.ensureStarted(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        HomeAction requested = HomeActions.consumeAction(intent);
        if (requested != null) automaticAction(requested);
        else if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            returnToMenu = true;
            if (!HomeActions.busy()) showMenu();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        HomeActions.visible(this);
        HomeMonitorService.ensureStarted(this);
    }

    @Override protected void onPause() {
        resumed = false;
        HomeActions.hidden(this);
        super.onPause();
    }

    void automaticAction() {
        automaticAction(HomeAction.GATE);
    }
    void automaticAction(HomeAction action) {
        if (action == HomeAction.MOP) return; // Manual-only, including future internal callers.
        if (!configurationSaved || HomeActions.busy() || PrivateImport.pending(this)) return;
        if (actionRunning || numberSetupContent.getVisibility() == View.VISIBLE
                || roborockSetup.getVisibility() == View.VISIBLE
                || (callStatusContent.getVisibility() == View.VISIBLE && errorActions.getVisibility() == View.VISIBLE)) return;
        returnToMenu = resumed && menuContent.getVisibility() == View.VISIBLE;
        currentAction = action;
        startSelected();
    }

    private void startSelected() { if (currentAction != HomeAction.GATE) startCleaning(); else startAttempt(); }

    private void showMenu() {
        hideKeyboard();
        roborockSetup.setVisibility(View.GONE);
        roborockInput.setText("");
        gateNumberInput.setText("");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        actionRunning = false;
        menuContent.setVisibility(View.VISIBLE);
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.GONE);
        HomeConfiguration config = HomeConfiguration.load(this);
        automationStatus.setText(PrivateImport.pending(this) ? R.string.import_failed : config == null || !config.enabled ? R.string.automation_not_configured
                : HomeMonitorService.ready(this) ? R.string.automation_enabled : R.string.automation_permissions_missing);
    }

    private void finishAction() {
        ++uiGeneration;
        statusIcon.removeCallbacks(finishAfterSuccess);
        statusIcon.removeCallbacks(finishAfterInformation);
        if (coordinator != null) { coordinator.close(); coordinator = null; }
        actionRunning = false;
        if (returnToMenu) showMenu();
        else finishAndRemoveTask();
    }

    @android.annotation.SuppressLint("GestureBackNavigation") // Legacy devices; API 33+ uses platform callback above.
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (actionRunning) finishAction();
        else if (menuContent.getVisibility() != View.VISIBLE) showMenu();
        else finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        statusIcon.removeCallbacks(finishAfterSuccess);
        statusIcon.removeCallbacks(finishAfterInformation);
        if (coordinator != null) {
            coordinator.close();
        }
        super.onDestroy();
    }

    private void startAttempt() {
        if (busyNotice()) return;
        currentAction = HomeAction.GATE;
        actionRunning = true;
        menuContent.setVisibility(View.GONE);
        gateNumber = gateNumberStore.read();
        if (gateNumber == null) {
            showNumberSetup();
            return;
        }

        long cooldownRemainingMillis = gateNumberStore.cooldownRemainingMillis();
        if (cooldownRemainingMillis > 0L) {
            showCooldownAndFinish(cooldownRemainingMillis);
            return;
        }

        statusIcon.removeCallbacks(finishAfterSuccess);
        statusIcon.removeCallbacks(finishAfterInformation);
        if (coordinator != null) {
            coordinator.close();
        }

        int generation = ++uiGeneration;
        if (!HomeActions.begin()) return;
        gateLease = true;
        roborockSetup.setVisibility(View.GONE);
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.VISIBLE);
        resetUi();
        coordinator = new GateCallCoordinator(
                this,
                gateNumber,
                gateNumberStore,
                new GateCallCoordinator.Listener() {
                    @Override public void onFinished() { releaseGateLease(); }

                    @Override
                    public void onStateChanged(
                            GateCallState state,
                            String title,
                            String description) {
                        if (generation == uiGeneration && !isFinishing()) {
                            renderState(state, title, description);
                        }
                    }

                    @Override
                    public void onSuccess() {
                        if (generation == uiGeneration && !isFinishing()) {
                            showSuccessAnimation();
                        }
                    }

                    @Override
                    public void onError(GateError error, String detail) {
                        if (generation != uiGeneration || isFinishing()) {
                            return;
                        }
                        if (error == GateError.RETRY_TOO_SOON) {
                            showCooldownAndFinish(Math.max(
                                    1000L,
                                    gateNumberStore.cooldownRemainingMillis()));
                        } else {
                            showError(error);
                        }
                    }
                });
        coordinator.start();
    }

    private void showNumberSetup() {
        ++uiGeneration;
        statusIcon.removeCallbacks(finishAfterSuccess); statusIcon.removeCallbacks(finishAfterInformation);
        roborockSetup.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        actionRunning = false;
        menuContent.setVisibility(View.GONE);
        numberSetupContent.setVisibility(View.VISIBLE);
        callStatusContent.setVisibility(View.GONE);
        gateNumberError.setVisibility(View.GONE);
    }

    private void saveGateNumber() {
        String normalized = GateNumberStore.normalize(gateNumberInput.getText().toString());
        if (normalized == null) {
            gateNumberError.setText(R.string.gate_number_invalid);
            gateNumberError.setVisibility(View.VISIBLE);
            gateNumberInput.requestFocus();
            return;
        }
        if (!gateNumberStore.save(normalized)) {
            gateNumberError.setText(R.string.gate_number_save_failed);
            gateNumberError.setVisibility(View.VISIBLE);
            return;
        }

        gateNumber = normalized;
        configurationSaved = true;
        gateNumberError.setVisibility(View.GONE);
        hideKeyboard();
        returnToMenu = true;
        showMenu();
        HomeMonitorService.ensureStarted(this);
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(gateNumberInput.getWindowToken(), 0);
        gateNumberInput.clearFocus();
    }

    private void resetUi() {
        updateActionIllustration();
        progress.setVisibility(View.VISIBLE);
        statusIcon.animate().cancel();
        statusIcon.setVisibility(View.GONE);
        statusIcon.setAlpha(1f);
        statusIcon.setScaleX(1f);
        statusIcon.setScaleY(1f);
        errorActions.setVisibility(View.GONE);
        statusTitle.setText(R.string.state_starting);
        statusDescription.setText(R.string.state_starting_description);
    }

    private void updateActionIllustration() {
        ((ImageView) findViewById(R.id.action_illustration)).setImageResource(
                currentAction == HomeAction.GATE ? R.drawable.art_gate
                        : currentAction == HomeAction.MOP ? R.drawable.art_mop : R.drawable.art_cleaning);
    }

    private void prepareActionTiles() {
        android.widget.LinearLayout row = findViewById(R.id.action_tiles);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        if (narrow) {
            row.setOrientation(android.widget.LinearLayout.VERTICAL);
            findViewById(R.id.home_subtitle).setVisibility(View.GONE);
            Button settings = findViewById(R.id.settings_button);
            settings.setTextSize(14);
            int padding = Math.round(12 * getResources().getDisplayMetrics().density);
            settings.setPaddingRelative(padding, 0, padding, 0);
        }
        for (int index = 0; index < row.getChildCount(); index++) {
            View tile = row.getChildAt(index);
            tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            if (narrow) {
                android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) tile.getLayoutParams();
                params.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT; params.weight = 0;
                params.setMarginStart(0); params.topMargin = index == 0 ? 0 : Math.round(16 * getResources().getDisplayMetrics().density);
                tile.setLayoutParams(params);
            }
        }
    }

    private void showCooldownAndFinish(long remainingMillis) {
        long remainingSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
        showInformation(
                R.string.retry_too_soon_title,
                getString(R.string.retry_too_soon_description, remainingSeconds),
                R.drawable.ic_error,
                R.string.error_icon_description);
    }

    private void showInformation(
            int titleResource,
            String description,
            int iconResource,
            int iconDescriptionResource) {
        ++uiGeneration;
        statusIcon.removeCallbacks(finishAfterInformation);
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        errorActions.setVisibility(View.GONE);
        statusIcon.animate().cancel();
        statusIcon.setImageResource(iconResource);
        statusIcon.setContentDescription(getString(iconDescriptionResource));
        statusIcon.setVisibility(View.VISIBLE);
        statusIcon.setAlpha(1f);
        statusIcon.setScaleX(1f);
        statusIcon.setScaleY(1f);
        statusTitle.setText(titleResource);
        statusDescription.setText(description);
        statusIcon.postDelayed(finishAfterInformation, INFORMATION_DISPLAY_MS);
    }

    private void renderState(GateCallState state, String title, String description) {
        statusTitle.setText(title);
        statusDescription.setText(description);

        if (state == GateCallState.ERROR) {
            progress.setVisibility(View.GONE);
            statusIcon.setImageResource(R.drawable.ic_error);
            statusIcon.setContentDescription(getString(R.string.error_icon_description));
            statusIcon.setVisibility(View.VISIBLE);
            // onError is posted only after Binder cleanup and the process lock are released.
            errorActions.setVisibility(View.GONE);
            return;
        }

        if (state == GateCallState.SUCCESS) {
            progress.setVisibility(View.GONE);
            errorActions.setVisibility(View.GONE);
            statusIcon.setImageResource(R.drawable.ic_success);
            statusIcon.setContentDescription(getString(R.string.success_icon_description));
            statusIcon.setVisibility(View.VISIBLE);
            return;
        }

        progress.setVisibility(View.VISIBLE);
        statusIcon.setVisibility(View.GONE);
        errorActions.setVisibility(View.GONE);
    }

    private void showError(GateError error) {
        actionRunning = false;
        progress.setVisibility(View.GONE);
        statusIcon.animate().cancel();
        statusIcon.setImageResource(R.drawable.ic_error);
        statusIcon.setContentDescription(getString(R.string.error_icon_description));
        statusIcon.setVisibility(View.VISIBLE);
        statusIcon.setAlpha(1f);
        statusIcon.setScaleX(1f);
        statusIcon.setScaleY(1f);
        statusTitle.setText(R.string.state_error);
        statusDescription.setText(
                getString(R.string.error_message_format, error.message(), error.code()));
        errorActions.setVisibility(View.VISIBLE);
    }

    private void showSuccessAnimation() {
        progress.setVisibility(View.GONE);
        errorActions.setVisibility(View.GONE);
        statusIcon.setImageResource(R.drawable.ic_success);
        statusIcon.setContentDescription(getString(R.string.success_icon_description));
        statusIcon.setVisibility(View.VISIBLE);
        statusIcon.setAlpha(0f);
        statusIcon.setScaleX(0.45f);
        statusIcon.setScaleY(0.45f);
        statusIcon.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(450L)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();
        statusIcon.postDelayed(finishAfterSuccess, SUCCESS_DISPLAY_MS);
    }

    private void releaseGateLease() { if (gateLease) { gateLease = false; HomeActions.end(); } }

    private void showRoborockSetup(boolean rejected) {
        ++uiGeneration;
        statusIcon.removeCallbacks(finishAfterSuccess); statusIcon.removeCallbacks(finishAfterInformation);
        actionRunning = false;
        menuContent.setVisibility(View.GONE); numberSetupContent.setVisibility(View.GONE); callStatusContent.setVisibility(View.GONE);
        roborockSetup.setVisibility(View.VISIBLE); roborockInput.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        roborockMessage.setText(rejected ? R.string.roborock_setup_rejected : R.string.roborock_setup_description);
    }

    private void startCleaning() {
        if (busyNotice()) return;
        RoborockStore store = new RoborockStore(this);
        RoborockCredentials credentials = store.read();
        if (credentials == null) { showRoborockSetup(store.rejected()); return; }
        if (currentAction == HomeAction.MOP && credentials.fullMopRoutine == 0) {
            showRoborockSetup(false);
            roborockMessage.setText(R.string.mop_setup_missing);
            return;
        }
        RoborockCredentials selected = credentials.forAction(currentAction);
        if (!HomeActions.begin()) return;
        actionRunning = true;
        int generation = ++uiGeneration;
        statusIcon.removeCallbacks(finishAfterSuccess); statusIcon.removeCallbacks(finishAfterInformation);
        menuContent.setVisibility(View.GONE); numberSetupContent.setVisibility(View.GONE); roborockSetup.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.VISIBLE); resetUi();
        statusTitle.setText(currentAction == HomeAction.MOP ? R.string.mop_starting : R.string.cleaning_starting);
        statusDescription.setText(R.string.cleaning_description);
        RoborockClient client = new RoborockClient();
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.function.Consumer<RoborockClient.Result> complete = result -> {
            if (!delivered.compareAndSet(false, true)) return;
            runOnUiThread(() -> {
                if (generation != uiGeneration || isFinishing() || isDestroyed()) return;
                renderCleaningResult(result);
                android.util.Log.i("DuduHome", currentAction.name() + " result " + result.name());
            });
        };
        timer.schedule(() -> { complete.accept(RoborockClient.Result.NETWORK_UNKNOWN); client.cancelTransport(); }, 20, java.util.concurrent.TimeUnit.SECONDS);
        new Thread(() -> {
            try {
                RoborockClient.Result result = client.execute(selected);
                if (result == RoborockClient.Result.AUTH_REJECTED) store.reject(credentials);
                complete.accept(result);
            } finally { timer.shutdownNow(); HomeActions.end(); }
        }, "RoborockRoutine").start();
    }

    private void renderCleaningResult(RoborockClient.Result result) {
        updateActionIllustration();
        actionRunning = false;
        if (result == RoborockClient.Result.AUTH_REJECTED) { showRoborockSetup(true); return; }
        menuContent.setVisibility(View.GONE); numberSetupContent.setVisibility(View.GONE); roborockSetup.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.VISIBLE);
        if (result == RoborockClient.Result.ACCEPTED) {
            statusTitle.setText(currentAction == HomeAction.MOP ? R.string.mop_accepted : R.string.cleaning_accepted);
            statusDescription.setText(R.string.cleaning_accepted_description);
            showSuccessAnimation();
        } else {
            progress.setVisibility(View.GONE); statusIcon.setImageResource(R.drawable.ic_error);
            statusIcon.setVisibility(View.VISIBLE);
            statusTitle.setText(currentAction == HomeAction.MOP ? R.string.mop_failed_title : R.string.cleaning_failed_title);
            statusDescription.setText(result == RoborockClient.Result.NETWORK_UNKNOWN ? R.string.cleaning_unknown : R.string.cleaning_error);
            errorActions.setVisibility(View.VISIBLE);
        }
    }

    private boolean busyNotice() {
        if (!HomeActions.busy()) return false;
        android.widget.Toast.makeText(this, R.string.operation_busy, android.widget.Toast.LENGTH_SHORT).show();
        return true;
    }
}
