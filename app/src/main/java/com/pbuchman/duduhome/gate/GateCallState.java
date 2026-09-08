package com.pbuchman.duduhome.gate;



public enum GateCallState {
    STARTING,
    BINDING,
    CHECKING_PHONE,
    READY,
    DIAL_REQUESTED,
    OUTGOING,
    WAITING_BEFORE_HANGUP,
    HANGING_UP,
    SUCCESS,
    ERROR
}
