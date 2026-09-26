package com.pbuchman.duduhome.ui;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeAction;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.ProgressBus;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.gate.GateCallCoordinator;
import com.pbuchman.duduhome.gate.GateCallState;
import com.pbuchman.duduhome.gate.GateError;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.location.HomeConfiguration;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.roborock.RoborockClient;
import com.pbuchman.duduhome.roborock.RoborockCredentials;
import com.pbuchman.duduhome.roborock.RoborockStore;

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
    public static final long SUCCESS_DISPLAY_MS = 5000L;
    private static final long INFORMATION_DISPLAY_MS = 2500L;

    private NavigationPanel navigation;
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
    private long actionAttempt;
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
        navigation = new NavigationPanel(this, () -> configurationSaved && !actionRunning
                && !HomeActions.busy() && !PrivateImport.pending(this)
                && menuContent.getVisibility() == View.VISIBLE, savedInstanceState);
        Button retryButton = findViewById(R.id.retry_button);
        Button closeButton = findViewById(R.id.close_button);
        Button saveNumberButton = findViewById(R.id.save_number_button);

        retryButton.setOnClickListener(view -> manualAction(currentAction));
        closeButton.setOnClickListener(view -> finishAction());
        findViewById(R.id.open_gate_button).setOnClickListener(view -> {
            if (actionRunning) return;
            manualAction(HomeAction.GATE);
        });
        findViewById(R.id.full_cleaning_button).setOnClickListener(view -> {
            manualAction(HomeAction.CLEANING);
        });
        findViewById(R.id.full_mop_button).setOnClickListener(view -> {
            manualAction(HomeAction.MOP);
        });
        findViewById(R.id.settings_button).setOnClickListener(view -> {
            if (busyNotice()) return;
            new android.app.AlertDialog.Builder(this).setTitle(R.string.settings)
                    .setItems(new String[]{"Numer bramy", "Dane dostępowe Roborock", "Lokalizacja",
                            getString(R.string.yanosik_notification_access), getString(R.string.navigation_import)}, (dialog, which) -> {
                        if (which == 0) { returnToMenu = true; showNumberSetup(); }
                        else if (which == 1) showRoborockSetup(false);
                        else if (which == 4) navigation.pick();
                        else if (which == 3) showYanosikAccess();
                        else new android.app.AlertDialog.Builder(this).setTitle("Lokalizacja")
                                .setMessage(HomeConfiguration.load(this) == null ? "Brak konfiguracji GPS. Dostarcz prywatny plik instalatorem."
                                        : "Konfiguracja GPS zainstalowana. Zmiany przez prywatny plik instalatora.")
                                .setPositiveButton("OK", null).show();
                    }).show();
        });
        findViewById(R.id.cancel_number_button).setOnClickListener(view -> { hideKeyboard(); showMenu(); });
        findViewById(R.id.cancel_roborock_button).setOnClickListener(view -> showMenu());
        findViewById(R.id.save_roborock_button).setOnClickListener(view -> {
            if (new RoborockStore(this).save(roborockInput.getText().toString())) {
                HomeActions.configurationChanged(); showMenu(); HomeMonitorService.ensureStarted(this);
            }
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
        HomeActions.Request requested = HomeActions.consumeRequest(this, getIntent());
        if (requested != null && configurationSaved) {
            currentAction = requested.action(); actionAttempt = requested.attempt(); returnToMenu = false; startSelected();
        } else if (gateNumber == null) {
            showNumberSetup();
        } else showMenu();
        if (!configurationSaved) automationStatus.setText(R.string.import_failed);
        if (!configurationSaved && requested != null)
            ProgressBus.update(this, requested.attempt(), Phase.SKIPPED, Reason.BUSY_OR_MAINTENANCE);
        HomeMonitorService.ensureStarted(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        HomeActions.Request requested = HomeActions.consumeRequest(this, intent);
        if (requested != null) automaticAction(requested.action(), requested.attempt());
        else if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            returnToMenu = true;
            if (!HomeActions.busy()) showMenu();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (navigation != null) navigation.resumed();
        HomeActions.visible(this);
        ProgressBus.presentationChanged();
        HomeMonitorService.ensureStarted(this);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (navigation != null) navigation.saveState(state);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (navigation != null) navigation.result(request, result, data);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); prepareActionTiles();
        if (navigation != null) navigation.render();
    }

    private void showYanosikAccess() {
        boolean granted = com.pbuchman.duduhome.automation.YanosikPresence.accessGranted(this);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.yanosik_notification_access)
                .setMessage(getString(granted ? R.string.yanosik_access_granted : R.string.yanosik_access_missing)
                        + "\n\n" + getString(R.string.yanosik_access_explanation))
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.yanosik_access_settings, (dialog, which) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                    catch (RuntimeException unavailable) {
                        new android.app.AlertDialog.Builder(this).setMessage(R.string.yanosik_access_unavailable)
                                .setPositiveButton("OK", null).show();
                    }
                }).show();
    }

    @Override protected void onPause() {
        resumed = false;
        HomeActions.hidden(this);
        ProgressBus.presentationChanged();
        super.onPause();
    }

    public void automaticAction() {
        automaticAction(HomeAction.GATE);
    }
    public boolean allowsExternalLaunch() {
        return isFinishing() || isDestroyed() || ((navigation == null || !navigation.busy()) && !actionRunning && menuContent.getVisibility() == View.VISIBLE
                && (!resumed || getWindow().getDecorView().hasWindowFocus()));
    }
    public void automaticAction(HomeAction action) {
        if (action == HomeAction.MOP) return;
        automaticAction(action, ProgressBus.request(this, ProgressBus.kind(action)));
    }
    public void automaticAction(HomeAction action, long attempt) {
        if (action == HomeAction.MOP) { ProgressBus.update(this, attempt, Phase.SKIPPED, Reason.UI_BUSY); return; }
        if (!configurationSaved || HomeActions.busy() || PrivateImport.pending(this)) {
            ProgressBus.update(this, attempt, Phase.SKIPPED, Reason.BUSY_OR_MAINTENANCE);
            com.pbuchman.duduhome.diagnostics.Diagnostics.record(this, "SKIP_AUTO_UI_NOT_READY"); return;
        }
        if ((navigation != null && navigation.busy()) || actionRunning || numberSetupContent.getVisibility() == View.VISIBLE
                || roborockSetup.getVisibility() == View.VISIBLE
                || (callStatusContent.getVisibility() == View.VISIBLE && errorActions.getVisibility() == View.VISIBLE)) {
            ProgressBus.update(this, attempt, Phase.SKIPPED, Reason.UI_BUSY);
            com.pbuchman.duduhome.diagnostics.Diagnostics.record(this, "SKIP_AUTO_UI_BUSY"); return;
        }
        returnToMenu = resumed && menuContent.getVisibility() == View.VISIBLE;
        currentAction = action;
        actionAttempt = attempt;
        startSelected();
    }

    private void manualAction(HomeAction action) {
        returnToMenu = true; currentAction = action;
        actionAttempt = ProgressBus.request(this, ProgressBus.kind(action)); startSelected();
    }
    public android.view.ViewGroup progressHost() {
        return resumed && menuContent.getVisibility() == View.VISIBLE && getWindow().getDecorView().hasWindowFocus()
                ? findViewById(R.id.automation_progress_host) : null;
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (menuContent != null) ProgressBus.presentationChanged();
        HomeActions.schedulingChanged();
    }

    private void startSelected() { if (currentAction != HomeAction.GATE) startCleaning(); else startAttempt(); }

    private void showMenu() {
        actionAttempt = 0;
        hideKeyboard();
        roborockSetup.setVisibility(View.GONE);
        roborockInput.setText("");
        gateNumberInput.setText("");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        actionRunning = false;
        menuContent.setVisibility(View.VISIBLE);
        navigation.render();
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.GONE);
        HomeActions.schedulingChanged();
        HomeConfiguration config = HomeConfiguration.load(this);
        automationStatus.setText(PrivateImport.pending(this) ? R.string.import_failed : config == null || !config.enabled ? R.string.automation_not_configured
                : HomeMonitorService.ready(this) ? R.string.automation_enabled : R.string.automation_permissions_missing);
    }

    private void finishAction() {
        if (coordinator != null) ProgressBus.update(this, actionAttempt, Phase.UNKNOWN, Reason.NONE);
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
            ProgressBus.update(this, actionAttempt, Phase.UNKNOWN, Reason.NONE);
            coordinator.close();
        }
        super.onDestroy();
        HomeActions.hidden(this);
    }

    private void startAttempt() {
        if (actionAttempt == 0) actionAttempt = ProgressBus.request(this, ProgressBus.kind(HomeAction.GATE));
        if (busyNotice()) { ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.UI_BUSY); return; }
        currentAction = HomeAction.GATE;
        actionRunning = true;
        menuContent.setVisibility(View.GONE);
        gateNumber = gateNumberStore.read();
        if (gateNumber == null) {
            ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.NO_NUMBER);
            showNumberSetup();
            return;
        }

        long cooldownRemainingMillis = gateNumberStore.cooldownRemainingMillis();
        if (cooldownRemainingMillis > 0L) {
            ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.COOLDOWN);
            showCooldownAndFinish(cooldownRemainingMillis);
            return;
        }

        statusIcon.removeCallbacks(finishAfterSuccess);
        statusIcon.removeCallbacks(finishAfterInformation);
        if (coordinator != null) {
            coordinator.close();
        }

        int generation = ++uiGeneration;
        if (!HomeActions.begin()) { ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.UI_BUSY); return; }
        final long attempt = actionAttempt;
        final Runnable gateSuccess = HomeActions.gateSuccessCallback();
        ProgressBus.update(this, attempt, Phase.ACCEPTED, Reason.NONE);
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
                        if (state != GateCallState.ERROR && state != GateCallState.SUCCESS)
                            ProgressBus.update(MainActivity.this, attempt, Phase.STARTED, Reason.NONE);
                        com.pbuchman.duduhome.diagnostics.Diagnostics.record(MainActivity.this, "GATE_STATE_" + state.name());
                        if (generation == uiGeneration && !isFinishing()) {
                            renderState(state, title, description);
                        }
                    }

                    @Override
                    public void onSuccess() {
                        gateSuccess.run();
                        ProgressBus.update(MainActivity.this, attempt, Phase.SUCCEEDED, Reason.NONE);
                        if (generation == uiGeneration && !isFinishing()) {
                            showSuccessAnimation();
                        }
                    }

                    @Override
                    public void onError(GateError error, String detail) {
                        ProgressBus.update(MainActivity.this, attempt, Phase.ERROR, Reason.NONE);
                        com.pbuchman.duduhome.diagnostics.Diagnostics.record(MainActivity.this, "GATE_ERROR_" + error.name());
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
        HomeActions.configurationChanged();
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
        row.setOrientation(narrow ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        Button settings = findViewById(R.id.settings_button);
        settings.setTextSize(narrow ? 14 : 18);
        int padding = Math.round((narrow ? 12 : 24) * getResources().getDisplayMetrics().density);
        settings.setPaddingRelative(padding, 0, padding, 0);
        for (int index = 0; index < row.getChildCount(); index++) {
            View tile = row.getChildAt(index);
            tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
            if (!narrow) {
                android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) tile.getLayoutParams();
                params.width = 0; params.weight = 1; params.topMargin = 0;
                params.setMarginStart(index == 0 ? 0 : Math.round(16 * getResources().getDisplayMetrics().density));
                tile.setLayoutParams(params);
            } else {
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
        if (actionAttempt == 0) actionAttempt = ProgressBus.request(this, ProgressBus.kind(currentAction));
        if (busyNotice()) { ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.UI_BUSY); return; }
        RoborockStore store = new RoborockStore(this);
        RoborockCredentials credentials = store.read();
        if (credentials == null) { ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.NO_CONFIG); showRoborockSetup(store.rejected()); return; }
        if (currentAction == HomeAction.MOP && credentials.fullMopRoutine == 0) {
            ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.NO_CONFIG);
            showRoborockSetup(false);
            roborockMessage.setText(R.string.mop_setup_missing);
            return;
        }
        RoborockCredentials selected = credentials.forAction(currentAction);
        if (!HomeActions.begin()) { ProgressBus.update(this, actionAttempt, Phase.SKIPPED, Reason.UI_BUSY); return; }
        final long attempt = actionAttempt;
        final HomeAction executing = currentAction;
        final Context application = getApplicationContext();
        ProgressBus.update(this, attempt, Phase.ACCEPTED, Reason.NONE);
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
            ProgressBus.update(application, attempt, result == RoborockClient.Result.ACCEPTED ? Phase.SUCCEEDED
                    : result == RoborockClient.Result.NETWORK_UNKNOWN ? Phase.UNKNOWN : Phase.ERROR, Reason.NONE);
            com.pbuchman.duduhome.diagnostics.Diagnostics.record(application, executing.name() + "_RESULT_" + result.name());
            runOnUiThread(() -> {
                if (generation != uiGeneration || isFinishing() || isDestroyed()) return;
                renderCleaningResult(result);
            });
        };
        timer.schedule(() -> { complete.accept(RoborockClient.Result.NETWORK_UNKNOWN); client.cancelTransport(); }, 20, java.util.concurrent.TimeUnit.SECONDS);
        new Thread(() -> {
            try {
                ProgressBus.update(application, attempt, Phase.STARTED, Reason.NONE);
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
        if (!HomeActions.busy() && (navigation == null || !navigation.busy())) return false;
        android.widget.Toast.makeText(this, R.string.operation_busy, android.widget.Toast.LENGTH_SHORT).show();
        return true;
    }
}
