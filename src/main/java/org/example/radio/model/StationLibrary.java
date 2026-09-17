package org.example.radio.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The station list, persisted as a tab separated file at
 * {@code ~/.config/tuner/stations.tsv} so it can be edited by hand.
 */
public final class StationLibrary {

    private final Path file;
    private final List<Station> stations = new ArrayList<>();

    public StationLibrary(Path file) {
        this.file = file;
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
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 2) continue;
                    String genre = parts.length > 2 ? parts[2] : "";
                    boolean fav = parts.length > 3 && parts[3].trim().equals("1");
                    stations.add(new Station(parts[0].trim(), parts[1].trim(), genre.trim(), fav));
                }
            }
        } catch (IOException ignored) {
            // Fall through to defaults.
        }
        if (stations.isEmpty()) {
            stations.addAll(DefaultStations.LIST);
            save();
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder("# name\turl\tgenre\tfavorite\n");
            for (Station s : stations) {
                sb.append(s.name()).append('\t').append(s.url()).append('\t')
                        .append(s.genre()).append('\t').append(s.favorite() ? '1' : '0').append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A read only home directory just means no persistence.
        }
    }
}
