package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.ProgressModel;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** One native layout for the menu and the non-interactive overlay. */
public final class AutomationBanner {
    private AutomationBanner() { }
    public static View create(Context c) { return LayoutInflater.from(c).inflate(R.layout.automation_progress, null); }
    public static void render(View view, ProgressModel.State state) {
        if (state.equals(view.getTag())) return;
        view.setTag(state);
        int title = switch (state.kind()) { case DEPARTURE -> R.string.progress_departure;
            case RETURN -> R.string.progress_return; case CLEANING -> R.string.full_cleaning;
            case YANOSIK -> R.string.progress_yanosik; case MOP -> R.string.full_mop; };
        int accent = switch (state.kind()) { case DEPARTURE, RETURN -> R.color.gate_sand;
            case CLEANING -> R.color.cleaning_mint; default -> R.color.mop_sky; };
        ((TextView) view.findViewById(R.id.banner_title)).setText(title);
        ((TextView) view.findViewById(R.id.banner_detail)).setText(detail(state));
        ((ImageView) view.findViewById(R.id.banner_icon)).setImageResource(switch (state.kind()) {
            case DEPARTURE, RETURN -> R.drawable.art_gate; case CLEANING -> R.drawable.art_cleaning;
            default -> R.drawable.ic_launcher;
        });
        ProgressBar bar = view.findViewById(R.id.banner_progress);
        bar.setProgressTintList(ColorStateList.valueOf(view.getContext().getColor(accent)));
        bar.setProgress((int) Math.round(state.value() * 1000));
        bar.setVisibility(ProgressModel.terminal(state.phase()) ? View.GONE : View.VISIBLE);
        view.setContentDescription(view.getContext().getString(title) + ". " + view.getContext().getString(detail(state)));
    }
    private static int detail(ProgressModel.State s) {
        if (s.reason() != Reason.NONE) return switch (s.reason()) {
            case STOPPED -> R.string.progress_stopped;
            case GPS_UNRELIABLE -> R.string.progress_bad_gps;
            case STALE -> R.string.progress_stale;
            case WAKE -> R.string.progress_wake;
            case CONFIGURATION -> R.string.progress_configuration;
            case SERVICE_STOPPED -> R.string.progress_service_stopped;
            case COOLDOWN -> R.string.progress_cooldown;
            case NO_NUMBER -> R.string.progress_no_number;
            case NO_CONFIG -> R.string.progress_no_config;
            case DAILY_LIMIT_OR_STORAGE -> R.string.progress_cleaning_blocked;
            case UI_UNAVAILABLE -> R.string.progress_ui_unavailable;
            case TARGET_UNAVAILABLE -> R.string.progress_target_unavailable;
            case TARGET_STATUS_UNKNOWN -> R.string.progress_target_status_unknown;
            case REQUEST_FAILED -> R.string.progress_request_failed;
            case BUSY_OR_MAINTENANCE, UI_BUSY -> R.string.operation_busy;
            default -> R.string.progress_conditions;
        };
        if (s.kind() == Kind.YANOSIK && s.phase() == Phase.SUCCEEDED) return R.string.progress_launch_requested;
        if (s.phase() == Phase.STARTED || s.phase() == Phase.ACCEPTED || s.phase() == Phase.REQUESTED)
            return R.string.progress_launching;
        if (s.phase() == Phase.CONFIRMED) return R.string.progress_confirmed;
        if (s.kind() == Kind.RETURN && s.stage() == Stage.APPROACHING_JUNCTION)
            return R.string.progress_return_checking_route;
        return switch (s.kind()) { case DEPARTURE -> R.string.progress_departure_detail;
            case RETURN -> R.string.progress_return_detail; case CLEANING -> R.string.progress_cleaning_detail;
            default -> R.string.progress_motion_detail; };
    }
}
