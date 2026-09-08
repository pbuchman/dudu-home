package com.pbuchman.duduhome.gate;



public enum PhoneState {
    DISCONNECTED,
    CONNECTING,
    IDLE,
    OUTGOING,
    INCOMING,
    ACTIVE,
    PAIRING,
    COMPLEX,
    UNKNOWN;

    public static PhoneState fromGlobalCode(int code) {
        return switch (code) {
            case 0 -> DISCONNECTED;
            case 1 -> CONNECTING;
            case 2 -> IDLE;
            case 3 -> OUTGOING;
            case 4 -> INCOMING;
            case 5 -> ACTIVE;
            case 6 -> PAIRING;
            case 7 -> COMPLEX;
            default -> UNKNOWN;
        };
    }

    public static PhoneState fromDeviceCode(int code) {
        return switch (code) {
            case 1 -> DISCONNECTED;
            case 2 -> CONNECTING;
            case 3 -> IDLE;
            case 4 -> OUTGOING;
            case 5 -> INCOMING;
            case 6 -> ACTIVE;
            default -> UNKNOWN;
        };
    }

    public boolean isBusy() {
        return this == OUTGOING || this == INCOMING || this == ACTIVE || this == COMPLEX;
    }
}
