package org.example.radio.audio;

public enum PlaybackState {
    IDLE("idle"),
    CONNECTING("connecting"),
    BUFFERING("buffering"),
    PLAYING("on air"),
    STOPPED("stopped"),
    RECONNECTING("reconnecting"),
    ERROR("error");

    private final String label;

    PlaybackState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
