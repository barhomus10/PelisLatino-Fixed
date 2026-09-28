package com.pelislatinohd.app.model;

import java.util.List;

public class Movie {
    public int id;
    public String type; // movie / series
    public String title;
    public String originalTitle;
    public String slug;
    public String date;
    public String poster;
    public String backdrop;
    public int year;
    public double rating;
    public double tmdbRating;
    public String rated;
    public int runtime;
    public String tmdb;
    public String embeddedId; // tt11923304
    public List<Integer> genres;
    public List<Integer> countries;
    public String country;
    public String synopsis;
    public List<Cast> cast;
    public List<Director> director;
    public List<Episode> episodes; // solo series

    public boolean isMovie() { return "movie".equals(type); }
    public boolean isAnime() {
        // anime si tiene género anime o país Japan + tipo series? simple heurística
        return false;
    }

    public static class Cast {
        public String name;
        public String role;
        public String photo;
    }
    public static class Director {
        public String name;
        public String role;
        public String photo;
    }
}
