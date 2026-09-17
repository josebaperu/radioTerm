package org.example.radio.audio;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.function.Consumer;

/**
 * ffmpeg only logs a track title when it changes, so the first title of a
 * session would never appear. This opens a short side connection with
 * {@code Icy-MetaData: 1}, grabs the current StreamTitle and disconnects,
 * repeating every few seconds while a station plays.
 */
public final class IcyMetadataPoller implements AutoCloseable {

    private static final int POLL_INTERVAL_MS = 15_000;
    private static final int MAX_REDIRECTS = 4;

    private final Thread worker;
    private volatile boolean running = true;

    public IcyMetadataPoller(String url, Consumer<String> onTitle) {
        worker = new Thread(() -> {
            while (running) {
                try {
                    String title = fetchTitle(url, MAX_REDIRECTS);
                    if (title != null && !title.isBlank() && running) onTitle.accept(title);
                } catch (Exception ignored) {
                    // Stream has no ICY metadata, or a transient network fault.
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "icy-poller");
        worker.setDaemon(true);
        worker.start();
    }

    private static String fetchTitle(String url, int redirectsLeft) throws IOException {
        URI uri = URI.create(url);
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        try (Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 6000);
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\n"
                    + "Host: " + uri.getHost() + "\r\n"
                    + "User-Agent: Tuner/1.0\r\n"
                    + "Icy-MetaData: 1\r\n"
                    + "Connection: close\r\n\r\n").getBytes());
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            int metaint = -1;
            String location = null;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("icy-metaint:")) {
                    metaint = Integer.parseInt(line.substring(12).trim());
                } else if (lower.startsWith("location:")) {
                    location = line.substring(9).trim();
                }
            }
            if (location != null && redirectsLeft > 0) {
                return fetchTitle(URI.create(url).resolve(location).toString(), redirectsLeft - 1);
            }
            if (metaint <= 0) return null;

            for (int attempt = 0; attempt < 3; attempt++) {
                in.skipNBytes(metaint);
                int blocks = in.read();
                if (blocks < 0) return null;
                if (blocks == 0) continue;
                byte[] payload = in.readNBytes(blocks * 16);
                String title = parseStreamTitle(new String(payload).trim());
                if (title != null && !title.isBlank()) return title;
            }
            return null;
        }
    }

    private static String parseStreamTitle(String metadata) {
        int start = metadata.indexOf("StreamTitle='");
        if (start < 0) return null;
        start += "StreamTitle='".length();
        int end = metadata.indexOf("';", start);
        if (end < 0) end = metadata.length();
        return metadata.substring(start, end).trim();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
