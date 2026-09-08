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
    private static final long SUCCESS_DISPLAY_MS = 1350L;
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

    private final Runnable finishAfterSuccess = this::finishAction;
    private final Runnable finishAfterInformation = this::finishAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
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
        automationStatus = findViewById(R.id.automation_status);
        gateNumberInput = findViewById(R.id.gate_number_input);
        gateNumberError = findViewById(R.id.gate_number_error);
        Button retryButton = findViewById(R.id.retry_button);
        Button closeButton = findViewById(R.id.close_button);
        Button saveNumberButton = findViewById(R.id.save_number_button);

        retryButton.setOnClickListener(view -> startAttempt());
        closeButton.setOnClickListener(view -> finishAction());
        findViewById(R.id.open_gate_button).setOnClickListener(view -> {
            if (actionRunning) return;
            returnToMenu = true;
            startAttempt();
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
        HomeConfiguration config = HomeConfiguration.load(this);
        if (config != null && config.verifiedPhone != null && !config.verifiedPhone.equals(gateNumberStore.read())) {
            configurationSaved = gateNumberStore.save(config.verifiedPhone);
        }
        gateNumber = configurationSaved ? gateNumberStore.read() : null;
        if (gateNumber == null) {
            showNumberSetup();
        } else if (HomeActions.consume(getIntent())) {
            returnToMenu = false;
            startAttempt();
        } else showMenu();
        HomeMonitorService.ensureStarted(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (HomeActions.consume(intent)) automaticAction();
        else if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            returnToMenu = true;
            if (!actionRunning && configurationSaved && gateNumberStore.read() != null) showMenu();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        HomeActions.visible(this);
        HomeMonitorService.ensureStarted(this);
        if (!actionRunning && configurationSaved && gateNumberStore != null && gateNumberStore.read() != null) showMenu();
    }

    @Override protected void onPause() {
        resumed = false;
        HomeActions.hidden(this);
        super.onPause();
    }

    void automaticAction() {
        if (!configurationSaved || actionRunning || gateNumberStore.read() == null || gateNumberStore.cooldownRemainingMillis() > 0) return;
        returnToMenu = resumed && menuContent.getVisibility() == View.VISIBLE;
        startAttempt();
    }

    private void showMenu() {
        actionRunning = false;
        menuContent.setVisibility(View.VISIBLE);
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.GONE);
        HomeConfiguration config = HomeConfiguration.load(this);
        automationStatus.setText(config == null || !config.enabled ? R.string.automation_not_configured
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
        numberSetupContent.setVisibility(View.GONE);
        callStatusContent.setVisibility(View.VISIBLE);
        resetUi();
        coordinator = new GateCallCoordinator(
                this,
                gateNumber,
                gateNumberStore,
                new GateCallCoordinator.Listener() {
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
}
