package org.example.radio.audio;

import org.example.radio.model.Station;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Owns the playback pipeline: ffmpeg decode, equalizer, volume, sound card.
 * One session thread runs per tuned station and reconnects on its own when a
 * stream drops.
 */
public final class Player implements AutoCloseable {

    public static final int SAMPLE_RATE = 44100;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
    private static final int FRAMES_PER_BLOCK = 1024;
    private static final int BLOCK_BYTES = FRAMES_PER_BLOCK * 4;
    private static final int LINE_BUFFER_BYTES = SAMPLE_RATE * 4 / 4; // ~250 ms

    private final Equalizer equalizer = new Equalizer(SAMPLE_RATE);
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer(SAMPLE_RATE);
    private final StreamInfo info = new StreamInfo();

    private volatile Session session;
    private volatile Station station;
    private volatile PlaybackState state = PlaybackState.IDLE;
    private volatile int volume = 70;
    private volatile boolean muted;
    private volatile double bufferHealth;
    private volatile String lastError = "";

    public Equalizer equalizer() {
        return equalizer;
    }

    public SpectrumAnalyzer spectrum() {
        return spectrum;
    }

    public StreamInfo info() {
        return info;
    }

    public Station station() {
        return station;
    }

    public PlaybackState state() {
        return state;
    }

    public double bufferHealth() {
        return bufferHealth;
    }

    public String lastError() {
        return lastError;
    }

    public int volume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setVolume(int value) {
        volume = Math.max(0, Math.min(100, value));
        if (volume > 0) muted = false;
    }

    public void adjustVolume(int delta) {
        setVolume(volume + delta);
    }

    public void toggleMute() {
        muted = !muted;
    }

    public boolean isActive() {
        return state == PlaybackState.PLAYING || state == PlaybackState.CONNECTING
                || state == PlaybackState.BUFFERING || state == PlaybackState.RECONNECTING;
    }

    /** Linear gain applied after the filter bank. */
    private float gain() {
        if (muted) return 0f;
        double v = volume / 100.0;
        return (float) (v * v);
    }

    public synchronized void play(Station target) {
        stop();
        station = target;
        info.clear();
        lastError = "";
        state = PlaybackState.CONNECTING;
        equalizer.flush();
        session = new Session(target);
        session.start();
    }

    public synchronized void stop() {
        Session current = session;
        session = null;
        if (current != null) current.shutdown();
        spectrum.silence();
        bufferHealth = 0;
        if (state != PlaybackState.ERROR) state = station == null ? PlaybackState.IDLE : PlaybackState.STOPPED;
    }

    /** Live streams cannot really pause, so this stops and re-tunes. */
    public synchronized void toggle() {
        if (isActive()) {
            stop();
        } else if (station != null) {
            play(station);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private final class Session {
        private final Station target;
        private final Thread thread;
        private volatile boolean stopping;
        private volatile StreamDecoder decoder;
        private volatile SourceDataLine line;
        private volatile IcyMetadataPoller poller;

        Session(Station target) {
            this.target = target;
            this.thread = new Thread(this::run, "player-" + target.name());
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void shutdown() {
            stopping = true;
            IcyMetadataPoller p = poller;
            if (p != null) p.close();
            StreamDecoder d = decoder;
            if (d != null) d.close();
            SourceDataLine l = line;
            if (l != null) {
                l.stop();
                l.flush();
            }
            thread.interrupt();
            try {
                thread.join(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run() {
            int attempt = 0;
            try {
                poller = new IcyMetadataPoller(target.url(), title -> {
                    if (!stopping && !title.isBlank()) info.title = title;
                });
                while (!stopping) {
                    boolean played = runOnce();
                    if (stopping) break;
                    if (played) {
                        attempt = 0;
                    } else if (++attempt >= 5) {
                        state = PlaybackState.ERROR;
                        lastError = info.error.isEmpty() ? "stream unavailable" : info.error;
                        return;
                    }
                    state = PlaybackState.RECONNECTING;
                    spectrum.silence();
                    Thread.sleep(Math.min(8000, 1000L * (1L << Math.min(attempt, 3))));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                state = PlaybackState.ERROR;
                lastError = String.valueOf(e.getMessage());
            } finally {
                IcyMetadataPoller p = poller;
                if (p != null) p.close();
            }
        }

        /** One connect and play cycle; returns true if any audio was heard. */
        private boolean runOnce() {
            byte[] raw = new byte[BLOCK_BYTES];
            float[] samples = new float[FRAMES_PER_BLOCK * 2];
            boolean produced = false;
            try (StreamDecoder d = new StreamDecoder(target.url(), SAMPLE_RATE, info)) {
                decoder = d;
                DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
                SourceDataLine out = (SourceDataLine) AudioSystem.getLine(lineInfo);
                out.open(FORMAT, LINE_BUFFER_BYTES);
                out.start();
                line = out;
                state = PlaybackState.BUFFERING;

                try {
                    while (!stopping) {
                        int filled = readFully(d, raw);
                        if (filled <= 0) break;
                        int frames = filled / 4;
                        toFloat(raw, filled, samples);
                        equalizer.process(samples, frames * 2, gain());
                        spectrum.feed(samples, frames * 2);
                        toBytes(samples, frames * 2, raw);
                        out.write(raw, 0, frames * 4);

                        produced = true;
                        if (state != PlaybackState.PLAYING) state = PlaybackState.PLAYING;
                        bufferHealth = 1.0 - (double) out.available() / out.getBufferSize();
                    }
                } finally {
                    line = null;
                    out.stop();
                    out.flush();
                    out.close();
                }
            } catch (LineUnavailableException e) {
                lastError = "no audio device: " + e.getMessage();
                state = PlaybackState.ERROR;
                stopping = true;
            } catch (Exception e) {
                if (!stopping) lastError = String.valueOf(e.getMessage());
            } finally {
                decoder = null;
            }
            return produced;
        }

        private int readFully(StreamDecoder d, byte[] buffer) throws java.io.IOException {
            int total = 0;
            while (total < buffer.length && !stopping) {
                int n = d.read(buffer, total, buffer.length - total);
                if (n < 0) return total > 0 ? total - (total % 4) : -1;
                total += n;
            }
            return total - (total % 4);
        }
    }

    private static void toFloat(byte[] raw, int length, float[] out) {
        int n = length / 2;
        for (int i = 0; i < n; i++) {
            int lo = raw[i * 2] & 0xFF;
            int hi = raw[i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768f;
        }
    }

    private static void toBytes(float[] samples, int count, byte[] out) {
        for (int i = 0; i < count; i++) {
            int v = Math.round(samples[i] * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}
