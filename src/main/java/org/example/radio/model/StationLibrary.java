package org.example.radio.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The station list.
 *
 * <p>Three sources, in order of precedence: the user's own
 * {@code ~/.config/tuner/stations.tsv}, the {@code stations.tsv} that ships
 * with the source tree, and finally the copy bundled inside the jar. Edits
 * always land in the user file, so the source file stays pristine.
 */
public final class StationLibrary {

    private static final String BUNDLED = "/stations.tsv";

    private final Path userFile;
    private final Path projectFile;
    private final List<Station> stations = new ArrayList<>();
    private String sourceLabel = "";

    public StationLibrary(Path userFile, Path projectFile) {
        this.userFile = userFile;
        this.projectFile = projectFile;
        load();
    }

    public List<Station> all() {
        return List.copyOf(stations);
    }

    public int size() {
        return stations.size();
    }

    public Station get(int index) {
        return stations.get(index);
    }

    /** Where the list currently in memory came from. */
    public String sourceLabel() {
        return sourceLabel;
    }

    public int indexOfUrl(String url) {
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).url().equals(url)) return i;
        }
        return -1;
    }

    public void add(Station station) {
        stations.add(station);
        save();
    }

    public void remove(int index) {
        if (index >= 0 && index < stations.size()) {
            stations.remove(index);
            save();
        }
    }

    public void toggleFavorite(int index) {
        if (index >= 0 && index < stations.size()) {
            Station s = stations.get(index);
            stations.set(index, s.withFavorite(!s.favorite()));
            save();
        }
    }

    /** Moves favourites to the top, keeping the relative order otherwise. */
    public void sortFavoritesFirst() {
        stations.sort((a, b) -> Boolean.compare(b.favorite(), a.favorite()));
        save();
    }

    private void load() {
        stations.clear();
        if (userFile != null && Files.exists(userFile) && readFile(userFile)) {
            sourceLabel = userFile.toString();
            return;
        }
        if (projectFile != null && Files.exists(projectFile) && readFile(projectFile)) {
            sourceLabel = projectFile.toString();
            return;
        }
        if (readBundled()) {
            sourceLabel = "bundled " + BUNDLED;
        }
    }

    private boolean readFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean readBundled() {
        try (InputStream in = StationLibrary.class.getResourceAsStream(BUNDLED)) {
            return in != null && parse(in);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean parse(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) continue;
            String genre = parts.length > 2 ? parts[2] : "";
            boolean fav = parts.length > 3 && parts[3].trim().equals("1");
            stations.add(new Station(parts[0].trim(), parts[1].trim(), genre.trim(), fav));
        }
        return !stations.isEmpty();
    }

    /** Persists to the user file; the source tree copy is never written to. */
    public void save() {
        if (userFile == null) return;
        try {
            Files.createDirectories(userFile.getParent());
            StringBuilder sb = new StringBuilder("# name\turl\tgenre\tfavorite\n");
            for (Station s : stations) {
                sb.append(s.name()).append('\t').append(s.url()).append('\t')
                        .append(s.genre()).append('\t').append(s.favorite() ? '1' : '0').append('\n');
            }
            Files.writeString(userFile, sb.toString(), StandardCharsets.UTF_8);
            sourceLabel = userFile.toString();
        } catch (IOException ignored) {
            // A read only home directory just means no persistence.
        }
    }
}
