package org.example.radio.model;

/** One tunable stream. */
public record Station(String name, String url, String genre, boolean favorite) {

    public Station(String name, String url, String genre) {
        this(name, url, genre, false);
    }

    public Station withFavorite(boolean value) {
        return new Station(name, url, genre, value);
    }

    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        return name.toLowerCase().contains(q)
                || genre.toLowerCase().contains(q)
                || url.toLowerCase().contains(q);
    }
}
