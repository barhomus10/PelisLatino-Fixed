package com.pelislatinohd.app.model;

import java.util.List;
import java.util.Map;

public class Catalog {
    public Meta meta;
    public List<Movie> movies;
    public List<Series> series;
    public Map<String, String> genres;
    public Map<String, String> countries;
    public List<GenreItem> genresList;
    public List<CountryItem> countriesList;
    public List<Episode> recentEpisodes;

    public static class Meta {
        public String title;
        public String base;
        public String generatedAt;
        public Total total;
    }
    public static class Total {
        public int movies;
        public int series;
        public int episodes;
    }
    public static class GenreItem {
        public int id;
        public String name;
        public String slug;
    }
    public static class CountryItem {
        public int id;
        public String name;
        public String slug;
    }
}
