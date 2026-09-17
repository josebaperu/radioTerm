package org.example.radio.audio;

/** Snapshot of what the decoder knows about the current stream. */
public final class StreamInfo {
    public volatile String icyName = "";
    public volatile String icyGenre = "";
    public volatile String bitrate = "";
    public volatile String codec = "";
    public volatile String sampleRate = "";
    public volatile String title = "";
    public volatile String error = "";

    public void clear() {
        icyName = icyGenre = bitrate = codec = sampleRate = title = error = "";
    }
}
