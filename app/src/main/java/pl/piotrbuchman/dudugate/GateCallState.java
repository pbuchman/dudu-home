package pl.piotrbuchman.dudugate;

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
