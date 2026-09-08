package com.pbuchman.duduhome.gate;



public enum GateError {
    BT_SERVICE_UNAVAILABLE(
            "BT_SERVICE_UNAVAILABLE",
            "Usługa telefonu Bluetooth DUDU jest niedostępna."),
    PHONE_NOT_CONNECTED(
            "PHONE_NOT_CONNECTED",
            "Telefon nie jest połączony i gotowy."),
    PHONE_BUSY(
            "PHONE_BUSY",
            "Telefon prowadzi lub zestawia inne połączenie."),
    MULTIPLE_PHONES(
            "MULTIPLE_PHONES",
            "Nie można bezpiecznie wybrać jednego telefonu."),
    RETRY_TOO_SOON(
            "RETRY_TOO_SOON",
            "Kolejna próba jest chwilowo zablokowana dla bezpieczeństwa."),
    DIAL_TIMEOUT(
            "DIAL_TIMEOUT",
            "Telefon nie potwierdził rozpoczęcia połączenia wychodzącego."),
    BT_CONNECTION_LOST(
            "BT_CONNECTION_LOST",
            "Połączenie z telefonem lub modułem Bluetooth zostało utracone."),
    HANGUP_TIMEOUT(
            "HANGUP_TIMEOUT",
            "Telefon nie potwierdził zakończenia połączenia."),
    IPC_ERROR(
            "IPC_ERROR",
            "Prywatny interfejs DUDU zwrócił nieoczekiwany błąd."),
    ATTEMPT_TIMEOUT(
            "ATTEMPT_TIMEOUT",
            "Próba przekroczyła maksymalny dozwolony czas."),
    ATTEMPT_IN_PROGRESS(
            "ATTEMPT_IN_PROGRESS",
            "Inna próba jest już uruchomiona.");

    private final String code;
    private final String message;

    GateError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
