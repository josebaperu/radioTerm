package org.example.radio.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps an ffmpeg child process that pulls a network stream and writes raw
 * signed 16 bit little endian PCM to stdout. The stderr side is mined for
 * ICY headers, codec details and errors.
 */
public final class StreamDecoder implements AutoCloseable {

    private static final Pattern META_LINE = Pattern.compile("^\\s{2,}(icy-[a-z]+|StreamTitle)\\s*:\\s*(.*)$");
    private static final Pattern TITLE_UPDATE = Pattern.compile("Metadata update for StreamTitle:\\s*(.*)$");
    private static final Pattern AUDIO_LINE = Pattern.compile("Audio:\\s*([A-Za-z0-9_]+).*?(\\d+)\\s*Hz.*?(\\d+)\\s*kb/s");

    private final Process process;
    private final InputStream pcm;
    private final Thread stderrPump;
    private final StreamInfo info;

    public StreamDecoder(String url, int sampleRate, StreamInfo info) throws IOException {
        this.info = info;
        List<String> cmd = new ArrayList<>(List.of(
                ffmpegBinary(),
                "-hide_banner", "-nostdin", "-nostats", "-loglevel", "info",
                "-user_agent", "Tuner/1.0",
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_on_network_error", "1",
                "-reconnect_delay_max", "5",
                "-icy", "1",
                "-i", url,
                "-vn",
                "-f", "s16le",
                "-acodec", "pcm_s16le",
                "-ac", "2",
                "-ar", Integer.toString(sampleRate),
                "-"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        process = pb.start();
        pcm = process.getInputStream();

        stderrPump = new Thread(this::pumpStderr, "ffmpeg-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    private static String ffmpegBinary() {
        String override = System.getenv("TUNER_FFMPEG");
        return override != null && !override.isBlank() ? override : "ffmpeg";
    }

    /** Reads decoded PCM; returns -1 at end of stream. */
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return pcm.read(buffer, offset, length);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    private void pumpStderr() {
        StringBuilder line = new StringBuilder();
        try (InputStream err = process.getErrorStream()) {
            int c;
            while ((c = err.read()) != -1) {
                if (c == '\n' || c == '\r') {
                    if (!line.isEmpty()) handleLine(line.toString());
                    line.setLength(0);
                } else {
                    line.append((char) c);
                }
            }
        } catch (IOException ignored) {
            // Process went away; the reader loop notices.
        }
        if (!line.isEmpty()) handleLine(line.toString());
    }

    private void handleLine(String raw) {
        Matcher update = TITLE_UPDATE.matcher(raw);
        if (update.find()) {
            info.title = clean(update.group(1));
            return;
        }
        Matcher meta = META_LINE.matcher(raw);
        if (meta.matches()) {
            String key = meta.group(1);
            String value = clean(meta.group(2));
            switch (key) {
                case "icy-name" -> { if (info.icyName.isEmpty()) info.icyName = value; }
                case "icy-genre" -> { if (info.icyGenre.isEmpty()) info.icyGenre = value; }
                case "icy-br" -> { if (info.bitrate.isEmpty()) info.bitrate = value + " kbps"; }
                case "StreamTitle" -> { if (info.title.isEmpty()) info.title = value; }
                default -> { }
            }
            return;
        }
        Matcher audio = AUDIO_LINE.matcher(raw);
        if (audio.find() && info.codec.isEmpty()) {
            info.codec = audio.group(1).toUpperCase();
            info.sampleRate = audio.group(2);
            if (info.bitrate.isEmpty()) info.bitrate = audio.group(3) + " kbps";
            return;
        }
        String lower = raw.toLowerCase();
        if (lower.contains("error") || lower.contains("failed") || lower.contains("not found")
                || lower.contains("invalid") || lower.contains("timed out") || lower.contains("refused")) {
            if (!lower.contains("broken pipe") && !lower.contains("muxing")) {
                info.error = clean(raw.replaceAll("^\\[[^]]*]\\s*", ""));
            }
        }
    }

    private static String clean(String s) {
        return s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            pcm.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        stderrPump.interrupt();
    }
}
