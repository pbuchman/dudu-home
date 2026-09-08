package com.pbuchman.duduhome.gate;



public final class GateConfig {
    public static final long HANGUP_DELAY_MS = 5000L;
    public static final long REATTEMPT_COOLDOWN_MS = 60_000L;
    public static final long BIND_TIMEOUT_MS = 3000L;
    public static final long DIAL_START_TIMEOUT_MS = 5000L;
    public static final long HANGUP_CONFIRM_TIMEOUT_MS = 5000L;
    public static final long TOTAL_ATTEMPT_TIMEOUT_MS = 25000L;

    private GateConfig() {
    }
}
