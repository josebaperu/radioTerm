package org.example.radio.ui;

import org.example.radio.RadioApp;
import org.example.radio.audio.Equalizer;
import org.example.radio.audio.PlaybackState;
import org.example.radio.audio.Player;
import org.example.radio.audio.StreamInfo;
import org.example.radio.model.Station;
import org.example.radio.tui.Screen;
import org.example.radio.tui.Style;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Draws the whole interface into the screen buffer, once per frame. */
public final class Ui {

    private static final String EIGHTHS = " ▁▂▃▄▅▆▇█";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final int MIN_WIDTH = 62;
    public static final int MIN_HEIGHT = 18;
    private static final int VOLUME_BAR_X = 5;
    private static final int VOLUME_BAR_W = 16;
    private static final int EQ_TRACK_X = 6;

    private Ui() {
    }

    public static void render(Screen s, RadioApp app) {
        s.clear();
        int w = s.width();
        int h = s.height();
        if (w < MIN_WIDTH || h < MIN_HEIGHT) {
            drawTooSmall(s, w, h);
            return;
        }

        Layout layout = layout(h);
        int leftW = stationPanelWidth(w);
        int rightX = leftW + 1;
        int rightW = w - leftW - 1;
        int mainH = layout.mainH();
        int nowPlayingH = mainH >= 15 ? 9 : mainH >= 11 ? 7 : Math.max(5, mainH - 5);
        int spectrumH = mainH - nowPlayingH;
        if (spectrumH < 3) {
            // Too short for a spectrum; give the rows to the now playing panel.
            nowPlayingH = mainH;
            spectrumH = 0;
        }

        drawHeader(s, app, w);
        drawStations(s, app, 0, layout.mainTop(), leftW, mainH);
        drawNowPlaying(s, app, rightX, layout.mainTop(), rightW, nowPlayingH);
        if (spectrumH >= 3) drawSpectrum(s, app, rightX, layout.mainTop() + nowPlayingH, rightW, spectrumH);
        drawEqualizer(s, app, 0, layout.eqTop(), w, layout.eqPanelH(), layout.eqRows());
        drawStatus(s, app, h - 2, w);
        drawFooter(s, app, h - 1, w);
        if (app.showHelp()) drawHelp(s, app, w, h);
    }

    /**
     * Vertical geometry. The equalizer owns the bottom half of the body, the
     * station list and meters share the top half.
     *
     * @param mainTop   first row of the upper half
     * @param mainH     height of the upper half
     * @param eqTop     first row of the equalizer panel
     * @param eqPanelH  height of the equalizer panel, including its border
     * @param eqRows    slider rows inside it, always odd so 0 dB sits centred
     */
    private record Layout(int mainTop, int mainH, int eqTop, int eqPanelH, int eqRows) {
    }

    private static Layout layout(int height) {
        int body = Math.max(8, height - 4);
        int eqPanelH = Math.max(7, body / 2);
        int eqRows = eqPanelH - 4;
        if (eqRows % 2 == 0) eqRows--;
        eqRows = Math.max(3, eqRows);
        int mainH = body - eqPanelH;
        return new Layout(2, mainH, 2 + mainH, eqPanelH, eqRows);
    }

    /** Regions the pointer can land on. */
    public enum Zone { NONE, STATION_ROW, EQ_BAND, VOLUME }

    /**
     * @param zone  what was clicked
     * @param index station row offset within the visible window, or band number
     * @param value volume percentage, or the gain the pointed row represents
     *              ({@link Integer#MIN_VALUE} when the row carries no gain)
     */
    public record Hit(Zone zone, int index, int value) {
        public static final Hit NONE = new Hit(Zone.NONE, -1, Integer.MIN_VALUE);
    }

    private static int stationPanelWidth(int width) {
        return Math.max(26, Math.min(42, width * 38 / 100));
    }

    /** Maps a pointer position onto the interface. */
    public static Hit hitTest(int width, int height, int x, int y) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return Hit.NONE;
        Layout l = layout(height);

        if (y == height - 2 && x >= VOLUME_BAR_X && x < VOLUME_BAR_X + VOLUME_BAR_W) {
            int percent = (int) Math.round((x - VOLUME_BAR_X) * 100.0 / (VOLUME_BAR_W - 1));
            return new Hit(Zone.VOLUME, -1, Math.max(0, Math.min(100, percent)));
        }
        if (x < stationPanelWidth(width) && y > l.mainTop() && y < l.mainTop() + l.mainH() - 1) {
            return new Hit(Zone.STATION_ROW, y - l.mainTop() - 1, Integer.MIN_VALUE);
        }
        if (y >= l.eqTop() && y < l.eqTop() + l.eqPanelH()) {
            int colW = Math.max(3, (width - 8) / Equalizer.BANDS);
            int band = -1;
            if (x >= EQ_TRACK_X && x < EQ_TRACK_X + colW * Equalizer.BANDS) {
                band = Math.min(Equalizer.BANDS - 1, (x - EQ_TRACK_X) / colW);
            }
            return new Hit(Zone.EQ_BAND, band, gainAtRow(height, y));
        }
        return Hit.NONE;
    }

    /**
     * Gain represented by a screen row inside the slider area, clamped at the
     * ends so a drag that runs past the panel still pins to a limit.
     */
    public static int gainAtRow(int height, int y) {
        if (height < MIN_HEIGHT) return Integer.MIN_VALUE;
        Layout l = layout(height);
        int top = l.eqTop() + 1;
        int rows = l.eqRows();
        if (y < l.eqTop() || y >= l.eqTop() + l.eqPanelH()) return Integer.MIN_VALUE;
        if (y < top) return Equalizer.MAX_GAIN_DB;
        if (y >= top + rows) return -Equalizer.MAX_GAIN_DB;
        double step = (2.0 * Equalizer.MAX_GAIN_DB) / (rows - 1);
        int gain = (int) Math.round(Equalizer.MAX_GAIN_DB - (y - top) * step);
        return Math.max(-Equalizer.MAX_GAIN_DB, Math.min(Equalizer.MAX_GAIN_DB, gain));
    }

    /** Number of station rows the current layout offers; used for scrolling. */
    public static int stationRows(int height) {
        if (height < MIN_HEIGHT) return 1;
        return Math.max(1, layout(height).mainH() - 2);
    }

    private static void drawTooSmall(Screen s, int w, int h) {
        String msg = "Terminal too small";
        String need = MIN_WIDTH + "x" + MIN_HEIGHT + " minimum, now " + w + "x" + h;
        s.put(Math.max(0, (w - msg.length()) / 2), Math.max(0, h / 2 - 1), msg, Theme.ALERT);
        s.put(Math.max(0, (w - need.length()) / 2), Math.max(0, h / 2), need, Theme.MUTED);
    }

    private static void drawHeader(Screen s, RadioApp app, int w) {
        Player p = app.player();
        int x = s.put(1, 0, "◉", Theme.GOLD_BOLD);
        x = s.put(x + 1, 0, "TUNER", Style.bold(Style.fg(Theme.C_WHITE)));
        s.put(x + 1, 0, "terminal radio", Theme.FAINT);

        String badge = stateBadge(p.state());
        String clock = LocalTime.now().format(CLOCK);
        StreamInfo info = p.info();
        String detail = info.codec.isEmpty() ? "" : info.codec.toLowerCase()
                + (info.bitrate.isEmpty() ? "" : " " + info.bitrate);

        int right = w - 1 - clock.length();
        s.put(right, 0, clock, Theme.FAINT);
        if (!detail.isEmpty()) {
            right -= detail.length() + 2;
            s.put(right, 0, detail, Theme.MUTED);
        }
        right -= badge.length() + 2;
        s.put(right, 0, badge, stateStyle(p.state()));

        s.hline(0, 1, w, '─', Theme.LINE);
    }

    private static String stateBadge(PlaybackState state) {
        return switch (state) {
            case PLAYING -> "● ON AIR";
            case BUFFERING -> "◐ BUFFERING";
            case CONNECTING -> "◌ CONNECTING";
            case RECONNECTING -> "◌ RECONNECTING";
            case STOPPED -> "■ STOPPED";
            case ERROR -> "✖ ERROR";
            case IDLE -> "○ IDLE";
        };
    }

    private static int stateStyle(PlaybackState state) {
        return switch (state) {
            case PLAYING -> Theme.LIVE;
            case BUFFERING, CONNECTING, RECONNECTING -> Theme.GOLD;
            case ERROR -> Theme.ALERT;
            default -> Theme.MUTED;
        };
    }

    private static void drawStations(Screen s, RadioApp app, int x, int y, int w, int h) {
        boolean focused = app.focus() == RadioApp.Focus.STATIONS;
        int border = focused ? Theme.ACCENT : Theme.LINE;
        List<Integer> visible = app.visibleStations();
        String title = "STATIONS " + (visible.isEmpty() ? "0" : (app.selectedIndex() + 1) + "/" + visible.size());
        s.box(x, y, w, h, title, border, focused ? Theme.ACCENT_BOLD : Theme.MUTED);

        int rows = h - 2;
        int innerW = w - 2;
        int playing = app.playingLibraryIndex();
        int scroll = app.scrollOffset();

        if (visible.isEmpty()) {
            s.putClipped(x + 2, y + 2, "no stations match", innerW - 2, Theme.FAINT);
            return;
        }
        for (int row = 0; row < rows; row++) {
            int idx = scroll + row;
            if (idx >= visible.size()) break;
            int libIndex = visible.get(idx);
            Station station = app.library().get(libIndex);
            boolean selected = idx == app.selectedIndex();
            boolean isPlaying = libIndex == playing;

            int base = selected ? Theme.SELECTED : Theme.TEXT;
            int dim = selected ? Theme.SELECTED_DIM : Theme.FAINT;
            int ly = y + 1 + row;
            if (selected) s.fill(x + 1, ly, innerW, 1, ' ', Theme.SELECTED);

            String marker = isPlaying ? "▶" : station.favorite() ? "★" : " ";
            int markerStyle = isPlaying
                    ? (selected ? Style.bold(Style.of(Theme.C_LIVE, Theme.C_SELECT_BG)) : Theme.LIVE)
                    : station.favorite()
                    ? (selected ? Style.bold(Style.of(Theme.C_GOLD, Theme.C_SELECT_BG)) : Theme.GOLD)
                    : dim;
            s.put(x + 1, ly, marker, markerStyle);

            int nameMax = innerW - 3;
            String genre = station.genre();
            boolean showGenre = !genre.isEmpty() && innerW > 30;
            if (showGenre) nameMax = innerW - 3 - Math.min(14, genre.length()) - 1;
            s.putClipped(x + 3, ly, station.name(), nameMax, base);
            if (showGenre) {
                String g = genre.length() > 14 ? genre.substring(0, 14) : genre;
                s.put(x + innerW - g.length(), ly, g, dim);
            }
        }
        if (visible.size() > rows) {
            drawScrollbar(s, x + w - 1, y + 1, rows, visible.size(), scroll);
        }
    }

    private static void drawScrollbar(Screen s, int x, int y, int rows, int total, int scroll) {
        int thumb = Math.max(1, rows * rows / total);
        int maxScroll = Math.max(1, total - rows);
        int pos = (int) Math.round((double) scroll / maxScroll * (rows - thumb));
        for (int i = 0; i < rows; i++) {
            boolean on = i >= pos && i < pos + thumb;
            s.set(x, y + i, on ? '┃' : '│', on ? Theme.ACCENT : Theme.LINE);
        }
    }

    private static void drawNowPlaying(Screen s, RadioApp app, int x, int y, int w, int h) {
        Player p = app.player();
        StreamInfo info = p.info();
        s.box(x, y, w, h, "NOW PLAYING", Theme.LINE, Theme.MUTED);
        int ix = x + 2;
        int iw = w - 4;
        if (iw < 4) return;

        Station station = p.station();
        int row = y + 1;
        if (station == null) {
            s.putClipped(ix, row + 1, "Nothing tuned in yet", iw, Theme.MUTED);
            s.putClipped(ix, row + 2, "Pick a station and press Enter", iw, Theme.FAINT);
            return;
        }

        s.putClipped(ix, row, station.name(), iw, Theme.TITLE);
        String title = info.title.isEmpty()
                ? (p.state() == PlaybackState.PLAYING ? "—" : stateBadge(p.state()).substring(2).toLowerCase())
                : info.title;
        s.put(ix, row + 1, marquee("♪ " + title, iw, app.tick()), Theme.ACCENT);

        String meta = station.genre();
        if (!info.icyName.isEmpty() && info.icyName.length() < 60) {
            meta = meta.isEmpty() ? info.icyName : meta + " · " + info.icyName;
        }
        s.putClipped(ix, row + 2, meta, iw, Theme.FAINT);

        if (!p.lastError().isEmpty() && p.state() == PlaybackState.ERROR) {
            s.putClipped(ix, row + 3, "✖ " + p.lastError(), iw, Theme.ALERT);
        } else if (h >= 9) {
            s.putClipped(ix, row + 3, station.url(), iw, Theme.FAINT);
        }

        if (h >= 7) drawMeters(s, p, ix, y + h - 2, iw, h >= 9);
    }

    private static void drawMeters(Screen s, Player p, int x, int y, int w, boolean twoRows) {
        int barW = Math.max(8, Math.min(w - 8, 44));
        if (twoRows) {
            drawMeter(s, "L", p.spectrum().levelLeft(), x, y - 1, barW);
            drawMeter(s, "R", p.spectrum().levelRight(), x, y, barW);
        } else {
            float mix = Math.max(p.spectrum().levelLeft(), p.spectrum().levelRight());
            drawMeter(s, "OUT", mix, x, y, barW);
        }
    }

    private static void drawMeter(Screen s, String label, float level, int x, int y, int w) {
        s.put(x, y, label, Theme.FAINT);
        int bx = x + label.length() + 1;
        int filled = (int) Math.round(Math.min(1f, level) * w);
        for (int i = 0; i < w; i++) {
            boolean on = i < filled;
            double t = (double) i / Math.max(1, w - 1);
            int color = t > 0.9 ? Theme.C_ALERT : t > 0.75 ? Theme.C_GOLD : Theme.C_LIVE;
            s.set(bx + i, y, on ? '▬' : '·', on ? Style.fg(color) : Theme.LINE);
        }
    }

    private static void drawSpectrum(Screen s, RadioApp app, int x, int y, int w, int h) {
        if (h < 3) return;
        Player p = app.player();
        boolean live = p.state() == PlaybackState.PLAYING;
        s.box(x, y, w, h, "SPECTRUM", Theme.LINE, Theme.MUTED);

        int ix = x + 1;
        int iy = y + 1;
        int iw = w - 2;
        int ih = h - 2;
        if (iw < 4 || ih < 1) return;

        int columns = iw / 2;
        double[] bars = p.spectrum().bars(columns);
        double[] peaks = p.spectrum().peaks();

        for (int c = 0; c < columns; c++) {
            double v = live ? bars[c] : 0;
            int cx = ix + c * 2;
            int totalEighths = (int) Math.round(v * ih * 8);
            for (int r = 0; r < ih; r++) {
                int rowFromBottom = ih - 1 - r;
                int eighths = Math.max(0, Math.min(8, totalEighths - rowFromBottom * 8));
                char ch = EIGHTHS.charAt(eighths);
                if (eighths == 0) continue;
                double heat = (double) rowFromBottom / Math.max(1, ih - 1);
                int style = Style.fg(Theme.heat(0.15 + heat * 0.85));
                s.set(cx, iy + r, ch, style);
                if (iw > columns * 2 - 1) s.set(cx + 1, iy + r, ch, style);
            }
            if (live && peaks.length > c && peaks[c] > 0.02) {
                int peakRow = ih - 1 - (int) Math.min(ih - 1, Math.round(peaks[c] * (ih - 1)));
                s.set(cx, iy + peakRow, '▔', Style.fg(Theme.C_WHITE));
                if (iw > columns * 2 - 1) s.set(cx + 1, iy + peakRow, '▔', Style.fg(Theme.C_WHITE));
            }
        }
        if (!live) {
            String idle = p.state() == PlaybackState.ERROR ? "signal lost" : "silence";
            s.put(ix + Math.max(0, (iw - idle.length()) / 2), iy + ih / 2, idle, Theme.FAINT);
        }
    }

    private static void drawEqualizer(Screen s, RadioApp app, int x, int y, int w, int h, int rows) {
        Player p = app.player();
        Equalizer eq = p.equalizer();
        boolean focused = app.focus() == RadioApp.Focus.EQUALIZER;
        int border = focused ? Theme.ACCENT : Theme.LINE;

        String preset = app.presetLabel();
        String title = "EQUALIZER · " + preset + " · " + (eq.isEnabled() ? "on" : "bypass")
                + " · pre " + signed(eq.preamp()) + " dB";
        s.box(x, y, w, h, title, border, focused ? Theme.ACCENT_BOLD : Theme.MUTED);

        int gutter = x + 1;
        int trackX = x + EQ_TRACK_X;
        int trackW = w - 8;
        int colW = Math.max(3, trackW / Equalizer.BANDS);
        int top = y + 1;
        int zeroRow = top + (rows - 1) / 2;

        s.put(gutter, top, "+" + Equalizer.MAX_GAIN_DB, Theme.FAINT);
        s.put(gutter, zeroRow, "  0", Theme.FAINT);
        s.put(gutter, top + rows - 1, "-" + Equalizer.MAX_GAIN_DB, Theme.FAINT);
        s.hline(trackX, zeroRow, colW * Equalizer.BANDS, '·', Theme.LINE);

        int handleW = Math.min(3, Math.max(1, colW - 1));
        int[] gains = eq.gains();
        boolean bypassed = !eq.isEnabled();

        for (int b = 0; b < Equalizer.BANDS; b++) {
            int cx = trackX + b * colW + (colW - handleW) / 2;
            int gain = gains[b];
            boolean selected = focused && b == app.eqBand();
            int handleRow = top + (int) Math.round((Equalizer.MAX_GAIN_DB - gain)
                    * (rows - 1) / (2.0 * Equalizer.MAX_GAIN_DB));
            // Keep a cut or boost off the zero line so small moves stay visible.
            if (gain != 0 && handleRow == zeroRow) handleRow += gain > 0 ? -1 : 1;

            int fillStyle = selected ? Style.fg(Theme.C_SELECT_FILL)
                    : bypassed ? Theme.LINE : Style.fg(Theme.C_ACCENT_SOFT);
            for (int r = 0; r < rows; r++) {
                int ry = top + r;
                if (ry == zeroRow) continue;
                boolean between = (ry > handleRow && ry < zeroRow) || (ry < handleRow && ry > zeroRow);
                if (between) {
                    for (int i = 0; i < handleW; i++) s.set(cx + i, ry, '▒', fillStyle);
                } else if (selected && ry != handleRow) {
                    // Faint guide so the whole selected column reads as active.
                    s.set(cx + handleW / 2, ry, '│', Theme.SELECT_GUIDE);
                }
            }

            double heat = (gain + Equalizer.MAX_GAIN_DB) / (2.0 * Equalizer.MAX_GAIN_DB);
            int handleStyle = selected ? Theme.SELECT_HANDLE
                    : bypassed ? Style.fg(Theme.C_FAINT) : Style.bold(Style.fg(Theme.heat(heat)));
            for (int i = 0; i < handleW; i++) {
                s.set(cx + i, handleRow, '█', handleStyle);
            }

            String label = Equalizer.LABELS[b];
            int labelX = trackX + b * colW + Math.max(0, (colW - label.length()) / 2);
            s.put(labelX, top + rows, label, selected ? Theme.SELECT_HANDLE : Theme.MUTED);

            String value = signed(gain);
            int valueX = trackX + b * colW + Math.max(0, (colW - value.length()) / 2);
            int valueStyle = gain == 0 ? Theme.FAINT : bypassed ? Theme.FAINT : Style.fg(Theme.heat(heat));
            s.put(valueX, top + rows + 1, value, selected ? Style.bold(valueStyle) : valueStyle);
        }
    }

    private static void drawStatus(Screen s, RadioApp app, int y, int w) {
        if (app.mode() != RadioApp.Mode.NORMAL) {
            drawPrompt(s, app, y, w);
            return;
        }
        Player p = app.player();
        int x = 1;
        x = s.put(x, y, "VOL ", Theme.MUTED);
        int barW = VOLUME_BAR_W;
        int filled = (int) Math.round(p.volume() / 100.0 * barW);
        for (int i = 0; i < barW; i++) {
            boolean on = i < filled && !p.isMuted();
            s.set(x + i, y, on ? '▰' : '▱', on ? Theme.ACCENT : Theme.LINE);
        }
        x += barW + 1;
        String vol = p.isMuted() ? "muted" : p.volume() + "%";
        x = s.put(x, y, String.format("%-6s", vol), p.isMuted() ? Theme.ALERT : Theme.TEXT);

        x = s.put(x + 1, y, "BUF ", Theme.MUTED);
        int bufW = 8;
        int bufFilled = (int) Math.round(Math.max(0, Math.min(1, p.bufferHealth())) * bufW);
        for (int i = 0; i < bufW; i++) {
            boolean on = i < bufFilled;
            s.set(x + i, y, on ? '▰' : '▱', on ? Theme.LIVE : Theme.LINE);
        }
        x += bufW + 2;

        String message = app.statusMessage();
        if (!message.isEmpty()) {
            s.putClipped(x, y, message, w - x - 1, Theme.GOLD);
        }
    }

    private static void drawPrompt(Screen s, RadioApp app, int y, int w) {
        String label = switch (app.mode()) {
            case SEARCH -> "search: ";
            case ADD_NAME -> "new station name: ";
            case ADD_URL -> "stream url: ";
            case CONFIRM_DELETE -> "delete station? (y/n) ";
            case NORMAL -> "";
        };
        int x = s.put(1, y, label, Theme.GOLD_BOLD);
        String buffer = app.inputBuffer();
        int max = w - x - 2;
        String shown = buffer.length() > max ? buffer.substring(buffer.length() - max) : buffer;
        x = s.put(x, y, shown, Theme.TITLE);
        if (app.mode() != RadioApp.Mode.CONFIRM_DELETE) {
            s.set(x, y, '▌', Theme.ACCENT_BOLD);
        }
    }

    private static void drawFooter(Screen s, RadioApp app, int y, int w) {
        String keys = app.focus() == RadioApp.Focus.STATIONS
                ? "↑↓ move  ⏎ play  space stop  tab eq  / search  f fav  a add  d del  +- vol  m mute  ? help  q quit"
                : "←→ band  ↑↓ gain  [ ] preset  e bypass  r reset  < > preamp  tab stations  +- vol  ? help  q quit";
        s.putClipped(1, y, keys, w - 2, Theme.FAINT);
    }

    private static void drawHelp(Screen s, RadioApp app, int w, int h) {
        String[] lines = {
                "STATIONS",
                "  ↑ / ↓ / j / k     move selection        ⏎          tune in",
                "  PgUp / PgDn       jump a page           space      stop or resume",
                "  b / n             previous / next       f          toggle favourite",
                "  / text            filter list           esc        clear filter",
                "  a                 add a station         d          delete station",
                "  s                 sort favourites first g / G      top / bottom",
                "",
                "EQUALIZER  (tab switches panels)",
                "  ← / →             select band           ↑ / ↓      band gain ±1 dB",
                "  [ / ]             previous / next preset            0-9 pick band",
                "  e                 bypass on or off      r          reset to flat",
                "  < / >             preamp ±1 dB",
                "",
                "MOUSE",
                "  click             select a row or band; click a row twice to tune in",
                "  wheel             over the list scrolls, over a band or the volume bar adjusts",
                "  drag              a slider follows the pointer; shift-drag selects text",
                "",
                "SOUND",
                "  + / -             volume                m          mute",
                "  q                 quit",
                "",
                "STATIONS FILE",
                "  " + app.library().sourceLabel()
        };
        int boxW = Math.min(w - 4, 72);
        int boxH = Math.min(h - 4, lines.length + 4);
        int x = (w - boxW) / 2;
        int y = (h - boxH) / 2;
        s.fill(x, y, boxW, boxH, ' ', Style.bg(Theme.TEXT, 233));
        s.box(x, y, boxW, boxH, "KEYS", Style.bg(Theme.ACCENT, 233), Style.bg(Theme.ACCENT_BOLD, 233));
        for (int i = 0; i < lines.length && i < boxH - 3; i++) {
            String line = lines[i];
            int style = line.startsWith("  ") ? Style.bg(Theme.TEXT, 233) : Style.bg(Theme.GOLD_BOLD, 233);
            s.putClipped(x + 2, y + 1 + i, line, boxW - 4, style);
        }
        String close = " press ? or esc to close ";
        s.put(x + boxW - close.length() - 2, y + boxH - 1, close, Style.bg(Theme.FAINT, 233));
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** Scrolls text that does not fit, at a readable pace. */
    private static String marquee(String text, int width, long tick) {
        if (text.length() <= width) return text;
        String padded = text + "   ·   ";
        int offset = (int) ((tick / 5) % padded.length());
        String doubled = padded + padded;
        return doubled.substring(offset, offset + width);
    }
}
