package com.pelislatinohd.app.data;

import androidx.room.*;
import java.util.List;

@Entity
class Favorite {
    @PrimaryKey public String slug;
    public String title;
    public String poster;
    public String type;
    public long addedAt;
}

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM Favorite ORDER BY addedAt DESC")
    List<Favorite> getAll();

    @Query("SELECT EXISTS(SELECT 1 FROM Favorite WHERE slug=:slug)")
    boolean exists(String slug);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Favorite f);

    @Query("DELETE FROM Favorite WHERE slug=:slug")
    void delete(String slug);
}

@Database(entities = {Favorite.class, HistoryItem.class}, version = 1, exportSchema = false)
abstract class AppDatabase extends RoomDatabase {
    public abstract FavoritesDao favoritesDao();
    public abstract HistoryDao historyDao();
}

@Entity
class HistoryItem {
    @PrimaryKey public String slug;
    public String title;
    public String poster;
    public long watchedAt;
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM HistoryItem ORDER BY watchedAt DESC LIMIT 50")
    List<HistoryItem> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(HistoryItem h);

    @Query("DELETE FROM HistoryItem WHERE slug=:slug")
    void delete(String slug);

    @Query("DELETE FROM HistoryItem")
    void clear();
}
