package org.example.radio;

import org.example.radio.audio.EqPreset;
import org.example.radio.audio.Equalizer;
import org.example.radio.audio.PlaybackState;
import org.example.radio.audio.Player;
import org.example.radio.config.Settings;
import org.example.radio.model.Station;
import org.example.radio.model.StationLibrary;
import org.example.radio.tui.Key;
import org.example.radio.tui.MouseEvent;
import org.example.radio.tui.Screen;
import org.example.radio.tui.Terminal;
import org.example.radio.ui.Ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Application state, key handling and the render loop. */
public final class RadioApp {

    public enum Focus { STATIONS, EQUALIZER }

    public enum Mode { NORMAL, SEARCH, ADD_NAME, ADD_URL, CONFIRM_DELETE }

    private static final long FRAME_MS = 40;
    private static final long MESSAGE_MS = 4000;
    private static final long DOUBLE_CLICK_MS = 450;

    private final Path configDir = configDirectory();
    private final Settings settings = new Settings(configDir.resolve("config.properties"));
    private final StationLibrary library = createLibrary(configDir);
    private final Player player = new Player();

    private final List<Integer> visible = new ArrayList<>();
    private String filter = "";
    private int selected;
    private int scroll;
    private Focus focus = Focus.STATIONS;
    private Mode mode = Mode.NORMAL;
    private String inputBuffer = "";
    private String pendingName = "";
    private int eqBand;
    private boolean showHelp;
    private boolean running = true;
    private long tick;
    private String message = "";
    private long messageAt;
    private int draggingBand = -1;
    private int lastClickRow = -1;
    private long lastClickAt;

    public static int run(String[] args) {
        RadioApp app = new RadioApp();
        return app.start(args);
    }

    private RadioApp() {
        rebuildVisible();
        loadSettings();
    }

    private static Path configDirectory() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("tuner");
    }

    /**
     * TUNER_STATIONS, when set, replaces both tiers: that file is read and
     * written directly. Otherwise the user copy wins over the source tree one.
     */
    private static StationLibrary createLibrary(Path configDir) {
        String override = System.getenv("TUNER_STATIONS");
        if (override != null && !override.isBlank()) {
            return new StationLibrary(Path.of(override), null);
        }
        return new StationLibrary(configDir.resolve("stations.tsv"), projectStationsFile());
    }

    /**
     * Finds the stations.tsv that ships with the source tree: the working
     * directory first, then upwards from wherever our classes were loaded
     * (target/classes when run from an IDE, target/tuner.jar otherwise).
     */
    private static Path projectStationsFile() {
        Path here = Path.of("stations.tsv").toAbsolutePath();
        if (java.nio.file.Files.exists(here)) return here;

        try {
            Path code = Path.of(RadioApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = java.nio.file.Files.isDirectory(code) ? code : code.getParent();
            for (int up = 0; dir != null && up < 4; up++, dir = dir.getParent()) {
                Path candidate = dir.resolve("stations.tsv");
                if (java.nio.file.Files.exists(candidate)) return candidate;
            }
        } catch (Exception ignored) {
            // Running from somewhere we cannot introspect; the bundled copy covers it.
        }
        return null;
    }

    private void loadSettings() {
        player.setVolume(settings.getInt("volume", 70));
        Equalizer eq = player.equalizer();
        eq.setGains(settings.getInts("eq.gains", new int[Equalizer.BANDS]));
        eq.setPreamp(settings.getInt("eq.preamp", 0));
        eq.setEnabled(settings.getBool("eq.enabled", true));
        String last = settings.getString("station.last", "");
        int idx = library.indexOfUrl(last);
        if (idx >= 0) {
            selected = visible.indexOf(idx);
            if (selected < 0) selected = 0;
        }
    }

    private void saveSettings() {
        settings.set("volume", player.volume());
        settings.setInts("eq.gains", player.equalizer().gains());
        settings.set("eq.preamp", player.equalizer().preamp());
        settings.set("eq.enabled", player.equalizer().isEnabled());
        if (player.station() != null) settings.set("station.last", player.station().url());
        settings.save();
    }

    private int start(String[] args) {
        String playQuery = null;
        boolean autoplay = settings.getBool("autoplay", true);
        boolean mouse = settings.getBool("mouse", true);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                case "-l", "--list" -> {
                    for (int n = 0; n < library.size(); n++) {
                        Station s = library.get(n);
                        System.out.printf("%3d  %-28s %s%n", n + 1, s.name(), s.url());
                    }
                    return 0;
                }
                case "-p", "--play" -> {
                    if (i + 1 < args.length) playQuery = args[++i];
                }
                case "--no-autoplay" -> autoplay = false;
                case "--no-mouse" -> mouse = false;
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    printUsage();
                    return 2;
                }
            }
        }

        if (!hasFfmpeg()) {
            System.err.println("ffmpeg was not found on PATH. Install it, or set TUNER_FFMPEG to its location.");
            return 1;
        }
        if (!Terminal.isInteractive()) {
            System.err.println("tuner needs an interactive terminal (stdin is not a tty).");
            return 1;
        }

        Station initial = null;
        if (playQuery != null) {
            initial = resolveStation(playQuery);
            if (initial == null) {
                System.err.println("no station matches: " + playQuery);
                return 2;
            }
        } else if (autoplay) {
            int idx = library.indexOfUrl(settings.getString("station.last", ""));
            if (idx >= 0) initial = library.get(idx);
        }

        Terminal terminal = new Terminal(mouse);
        Runtime.getRuntime().addShutdownHook(new Thread(terminal::close));
        Screen screen = new Screen();
        try {
            screen.resize(terminal.width(), terminal.height());
            if (initial != null) {
                selectStation(initial);
                tune(initial);
            }
            loop(terminal, screen);
        } finally {
            player.close();
            saveSettings();
            terminal.close();
        }
        return 0;
    }

    private static boolean hasFfmpeg() {
        try {
            String bin = System.getenv("TUNER_FFMPEG");
            Process p = new ProcessBuilder(bin == null || bin.isBlank() ? "ffmpeg" : bin, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Station resolveStation(String query) {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return new Station("Custom stream", query, "cli");
        }
        try {
            int n = Integer.parseInt(query.trim());
            if (n >= 1 && n <= library.size()) return library.get(n - 1);
        } catch (NumberFormatException ignored) {
            // Not an index, fall through to a name match.
        }
        for (int i = 0; i < library.size(); i++) {
            if (library.get(i).name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                return library.get(i);
            }
        }
        return null;
    }

    private static void printUsage() {
        System.out.println("""
                tuner - terminal internet radio with a 10 band equalizer

                usage: tuner [options]
                  -l, --list            print the station list and exit
                  -p, --play <what>     tune a station by number, name or stream url
                      --no-autoplay     start without resuming the last station
                      --no-mouse        disable mouse reporting for this run
                  -h, --help            show this help

                stations:  stations.tsv in the source tree, overridden by
                           ~/.config/tuner/stations.tsv once you edit the list
                           (or point TUNER_STATIONS at a file of your own)
                settings:  ~/.config/tuner/config.properties""");
    }

    private void loop(Terminal terminal, Screen screen) {
        long nextFrame = 0;
        while (running) {
            Key key = terminal.readKey(12);
            if (!key.isNone()) handleKey(key);

            if (terminal.refreshSize(false)) {
                screen.resize(terminal.width(), terminal.height());
                screen.invalidate();
            }
            long now = System.currentTimeMillis();
            if (now >= nextFrame) {
                nextFrame = now + FRAME_MS;
                tick++;
                clampSelection(screen.width(), screen.height());
                StringBuilder out = new StringBuilder(8192);
                Ui.render(screen, this);
                screen.render(out);
                terminal.write(out);
            }
        }
    }

    private void handleKey(Key key) {
        if (key.type() == Key.Type.MOUSE) {
            handleMouse(key.mouse());
            return;
        }
        if (mode != Mode.NORMAL) {
            handleInputMode(key);
            return;
        }
        if (showHelp) {
            showHelp = key.type() != Key.Type.ESCAPE && !key.is('?') && !key.is('q') && key.type() != Key.Type.ENTER;
            if (key.is('q') && !showHelp) return;
            if (!showHelp) return;
        }

        switch (key.type()) {
            case TAB, SHIFT_TAB -> {
                focus = focus == Focus.STATIONS ? Focus.EQUALIZER : Focus.STATIONS;
                return;
            }
            case ESCAPE -> {
                if (!filter.isEmpty()) {
                    filter = "";
                    rebuildVisible();
                    note("filter cleared");
                }
                return;
            }
            default -> {
                // Handled below.
            }
        }

        if (key.is('q')) {
            running = false;
            return;
        }
        if (key.is('?')) {
            showHelp = true;
            return;
        }
        if (key.is('+') || key.is('=')) {
            player.adjustVolume(5);
            note("volume " + player.volume() + "%");
            return;
        }
        if (key.is('-') || key.is('_')) {
            player.adjustVolume(-5);
            note("volume " + player.volume() + "%");
            return;
        }
        if (key.is('m')) {
            player.toggleMute();
            note(player.isMuted() ? "muted" : "unmuted");
            return;
        }
        if (key.is(' ')) {
            player.toggle();
            note(player.isActive() ? "resuming" : "stopped");
            return;
        }
        if (key.is('e')) {
            Equalizer eq = player.equalizer();
            eq.setEnabled(!eq.isEnabled());
            note(eq.isEnabled() ? "equalizer on" : "equalizer bypassed");
            return;
        }
        if (key.is('r')) {
            player.equalizer().reset();
            note("equalizer flat");
            return;
        }
        if (key.is('[')) {
            cyclePreset(-1);
            return;
        }
        if (key.is(']')) {
            cyclePreset(1);
            return;
        }
        if (key.is('<') || key.is(',')) {
            player.equalizer().setPreamp(player.equalizer().preamp() - 1);
            note("preamp " + player.equalizer().preamp() + " dB");
            return;
        }
        if (key.is('>') || key.is('.')) {
            player.equalizer().setPreamp(player.equalizer().preamp() + 1);
            note("preamp " + player.equalizer().preamp() + " dB");
            return;
        }

        if (focus == Focus.EQUALIZER) {
            handleEqualizerKey(key);
        } else {
            handleStationKey(key);
        }
    }

    /** Clicks, wheel and drags, mapped onto the panel under the pointer. */
    private void handleMouse(MouseEvent event) {
        if (showHelp) {
            if (event.action() == MouseEvent.Action.PRESS) showHelp = false;
            return;
        }
        if (mode != Mode.NORMAL) return;

        if (event.action() == MouseEvent.Action.RELEASE) {
            draggingBand = -1;
            return;
        }
        // A drag that started on a slider keeps following the pointer vertically,
        // even once it leaves the column.
        if (event.action() == MouseEvent.Action.DRAG && draggingBand >= 0) {
            int gain = Ui.gainAtRow(lastHeight, event.y());
            if (gain != Integer.MIN_VALUE) player.equalizer().setGain(draggingBand, gain);
            return;
        }

        Ui.Hit hit = Ui.hitTest(lastWidth, lastHeight, event.x(), event.y());
        if (event.isWheel()) {
            handleWheel(event, hit);
            return;
        }
        if (event.button() != MouseEvent.Button.LEFT || event.action() != MouseEvent.Action.PRESS) return;

        switch (hit.zone()) {
            case STATION_ROW -> clickStation(hit.index());
            case EQ_BAND -> {
                focus = Focus.EQUALIZER;
                if (hit.index() >= 0) {
                    eqBand = hit.index();
                    if (hit.value() != Integer.MIN_VALUE) {
                        player.equalizer().setGain(eqBand, hit.value());
                        draggingBand = eqBand;
                    }
                }
            }
            case VOLUME -> {
                player.setVolume(hit.value());
                note("volume " + player.volume() + "%");
            }
            case NONE -> { }
        }
    }

    private void handleWheel(MouseEvent event, Ui.Hit hit) {
        int direction = event.button() == MouseEvent.Button.WHEEL_UP ? 1 : -1;
        switch (hit.zone()) {
            case STATION_ROW -> {
                focus = Focus.STATIONS;
                moveSelection(-direction);
            }
            case EQ_BAND -> {
                focus = Focus.EQUALIZER;
                if (hit.index() >= 0) eqBand = hit.index();
                Equalizer eq = player.equalizer();
                eq.setGain(eqBand, eq.gain(eqBand) + direction);
            }
            case VOLUME -> {
                player.adjustVolume(direction * 5);
                note("volume " + player.volume() + "%");
            }
            case NONE -> { }
        }
    }

    /** Single click selects, a second click on the same row tunes in. */
    private void clickStation(int rowOffset) {
        int index = scroll + rowOffset;
        if (index < 0 || index >= visible.size()) return;
        focus = Focus.STATIONS;
        selected = index;
        long now = System.currentTimeMillis();
        if (index == lastClickRow && now - lastClickAt < DOUBLE_CLICK_MS) {
            tuneSelected();
            lastClickRow = -1;
        } else {
            lastClickRow = index;
            lastClickAt = now;
        }
    }

    private void handleEqualizerKey(Key key) {
        Equalizer eq = player.equalizer();
        switch (key.type()) {
            case LEFT -> eqBand = (eqBand + Equalizer.BANDS - 1) % Equalizer.BANDS;
            case RIGHT -> eqBand = (eqBand + 1) % Equalizer.BANDS;
            case UP -> eq.setGain(eqBand, eq.gain(eqBand) + 1);
            case DOWN -> eq.setGain(eqBand, eq.gain(eqBand) - 1);
            case HOME -> eqBand = 0;
            case END -> eqBand = Equalizer.BANDS - 1;
            case CHAR -> {
                char c = key.ch();
                if (c == 'h') eqBand = (eqBand + Equalizer.BANDS - 1) % Equalizer.BANDS;
                else if (c == 'l') eqBand = (eqBand + 1) % Equalizer.BANDS;
                else if (c == 'k') eq.setGain(eqBand, eq.gain(eqBand) + 1);
                else if (c == 'j') eq.setGain(eqBand, eq.gain(eqBand) - 1);
                else if (c >= '0' && c <= '9') eqBand = c == '0' ? 9 : c - '1';
            }
            default -> {
                // Nothing else is bound in this panel.
            }
        }
    }

    private void handleStationKey(Key key) {
        int rows = Math.max(1, Ui.stationRows(lastHeight));
        switch (key.type()) {
            case UP -> moveSelection(-1);
            case DOWN -> moveSelection(1);
            case PAGE_UP -> moveSelection(-rows);
            case PAGE_DOWN -> moveSelection(rows);
            case HOME -> selected = 0;
            case END -> selected = Math.max(0, visible.size() - 1);
            case ENTER -> tuneSelected();
            case CHAR -> {
                char c = key.ch();
                switch (c) {
                    case 'j' -> moveSelection(1);
                    case 'k' -> moveSelection(-1);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = Math.max(0, visible.size() - 1);
                    case 'b' -> {
                        moveSelection(-1);
                        tuneSelected();
                    }
                    case 'n' -> {
                        moveSelection(1);
                        tuneSelected();
                    }
                    case 'f' -> {
                        if (!visible.isEmpty()) {
                            int libIndex = visible.get(selected);
                            library.toggleFavorite(libIndex);
                            note(library.get(libIndex).favorite() ? "favourited" : "unfavourited");
                        }
                    }
                    case 's' -> {
                        library.sortFavoritesFirst();
                        rebuildVisible();
                        note("favourites first");
                    }
                    case '/' -> {
                        mode = Mode.SEARCH;
                        inputBuffer = filter;
                    }
                    case 'a' -> {
                        mode = Mode.ADD_NAME;
                        inputBuffer = "";
                    }
                    case 'd' -> {
                        if (!visible.isEmpty()) {
                            mode = Mode.CONFIRM_DELETE;
                            inputBuffer = library.get(visible.get(selected)).name();
                        }
                    }
                    default -> {
                        // Unbound key.
                    }
                }
            }
            default -> {
                // Unbound key.
            }
        }
    }

    private void handleInputMode(Key key) {
        switch (key.type()) {
            case ESCAPE -> {
                if (mode == Mode.SEARCH) {
                    filter = "";
                    rebuildVisible();
                }
                mode = Mode.NORMAL;
                inputBuffer = "";
            }
            case BACKSPACE -> {
                if (!inputBuffer.isEmpty()) {
                    inputBuffer = inputBuffer.substring(0, inputBuffer.length() - 1);
                    if (mode == Mode.SEARCH) {
                        filter = inputBuffer;
                        rebuildVisible();
                    }
                }
            }
            case ENTER -> commitInput();
            case CHAR -> {
                if (mode == Mode.CONFIRM_DELETE) {
                    if (key.ch() == 'y' || key.ch() == 'Y') {
                        int libIndex = visible.get(selected);
                        String name = library.get(libIndex).name();
                        library.remove(libIndex);
                        rebuildVisible();
                        note("removed " + name);
                    }
                    mode = Mode.NORMAL;
                    inputBuffer = "";
                    return;
                }
                inputBuffer += key.ch();
                if (mode == Mode.SEARCH) {
                    filter = inputBuffer;
                    rebuildVisible();
                }
            }
            default -> {
                // Ignored while typing.
            }
        }
    }

    private void commitInput() {
        switch (mode) {
            case SEARCH -> {
                filter = inputBuffer;
                rebuildVisible();
                mode = Mode.NORMAL;
                inputBuffer = "";
            }
            case ADD_NAME -> {
                if (inputBuffer.isBlank()) {
                    mode = Mode.NORMAL;
                } else {
                    pendingName = inputBuffer.trim();
                    mode = Mode.ADD_URL;
                }
                inputBuffer = "";
            }
            case ADD_URL -> {
                String url = inputBuffer.trim();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    library.add(new Station(pendingName, url, "custom"));
                    rebuildVisible();
                    selected = visible.indexOf(library.size() - 1);
                    if (selected < 0) selected = Math.max(0, visible.size() - 1);
                    note("added " + pendingName);
                } else {
                    note("that does not look like a stream url");
                }
                mode = Mode.NORMAL;
                inputBuffer = "";
                pendingName = "";
            }
            default -> {
                mode = Mode.NORMAL;
                inputBuffer = "";
            }
        }
    }

    private void cyclePreset(int direction) {
        List<EqPreset> presets = EqPreset.ALL;
        int current = EqPreset.matching(player.equalizer().gains());
        int next = current < 0
                ? (direction > 0 ? 0 : presets.size() - 1)
                : (current + direction + presets.size()) % presets.size();
        EqPreset preset = presets.get(next);
        player.equalizer().apply(preset);
        player.equalizer().setEnabled(true);
        note("preset " + preset.name());
    }

    private void moveSelection(int delta) {
        if (visible.isEmpty()) return;
        selected = Math.max(0, Math.min(visible.size() - 1, selected + delta));
    }

    private void tuneSelected() {
        if (visible.isEmpty()) return;
        tune(library.get(visible.get(selected)));
    }

    /** Moves the list cursor onto a station, when it is one we know about. */
    private void selectStation(Station station) {
        int libIndex = library.indexOfUrl(station.url());
        if (libIndex < 0) return;
        int idx = visible.indexOf(libIndex);
        if (idx >= 0) selected = idx;
    }

    private void tune(Station station) {
        player.play(station);
        note("tuning " + station.name());
        settings.set("station.last", station.url());
    }

    private void rebuildVisible() {
        Integer previous = visible.isEmpty() || selected >= visible.size() ? null : visible.get(selected);
        visible.clear();
        for (int i = 0; i < library.size(); i++) {
            if (library.get(i).matches(filter)) visible.add(i);
        }
        if (previous != null) {
            int idx = visible.indexOf(previous);
            selected = idx >= 0 ? idx : 0;
        }
        if (selected >= visible.size()) selected = Math.max(0, visible.size() - 1);
    }

    private int lastHeight = 24;
    private int lastWidth = 80;

    private void clampSelection(int width, int height) {
        lastWidth = width;
        lastHeight = height;
        int rows = Ui.stationRows(height);
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + rows) scroll = selected - rows + 1;
        int maxScroll = Math.max(0, visible.size() - rows);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    private void note(String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    public Player player() {
        return player;
    }

    public StationLibrary library() {
        return library;
    }

    public List<Integer> visibleStations() {
        return visible;
    }

    public int selectedIndex() {
        return selected;
    }

    public int scrollOffset() {
        return scroll;
    }

    public int playingLibraryIndex() {
        Station s = player.station();
        if (s == null || player.state() == PlaybackState.IDLE) return -1;
        return library.indexOfUrl(s.url());
    }

    public Focus focus() {
        return focus;
    }

    public Mode mode() {
        return mode;
    }

    public String inputBuffer() {
        return inputBuffer;
    }

    public int eqBand() {
        return eqBand;
    }

    public boolean showHelp() {
        return showHelp;
    }

    public long tick() {
        return tick;
    }

    public String presetLabel() {
        int idx = EqPreset.matching(player.equalizer().gains());
        return idx < 0 ? "Custom" : EqPreset.ALL.get(idx).name();
    }

    public String statusMessage() {
        if (message.isEmpty() || System.currentTimeMillis() - messageAt > MESSAGE_MS) return "";
        return message;
    }
}
