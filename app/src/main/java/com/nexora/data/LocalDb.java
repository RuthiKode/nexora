package com.nexora.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;

import com.nexora.BuildConfig;

import java.util.List;

/**
 * Room database for data that must work offline regardless of Firestore cache state:
 * bookmarks and readiness activities (local-first) and the last successful drive/application fetch.
 */
@Database(entities = {LocalDb.Bookmark.class, LocalDb.ActivityRow.class, LocalDb.CachedDrive.class,
        LocalDb.CachedApplication.class}, version = 1, exportSchema = false)
public abstract class LocalDb extends RoomDatabase {

    @Entity(tableName = "bookmarks", primaryKeys = {"uid", "driveId"})
    public static class Bookmark {
        @NonNull public String uid = "";
        @NonNull public String driveId = "";
        public long createdAt;
    }

    @Entity(tableName = "readiness_activities")
    public static class ActivityRow {
        @PrimaryKey @NonNull public String id = "";
        public String uid, type, title;
        public Integer scorePercent, rating;
        public int durationMin;
        public long date, createdAt;
        public boolean synced;
    }

    @Entity(tableName = "cached_drives")
    public static class CachedDrive {
        @PrimaryKey @NonNull public String id = "";
        public String json;
        public long cachedAt;
    }

    @Entity(tableName = "cached_applications")
    public static class CachedApplication {
        @PrimaryKey @NonNull public String id = "";
        public String uid, json;
        public long cachedAt;
    }

    @Dao
    public interface Store {
        @Query("SELECT driveId FROM bookmarks WHERE uid = :uid ORDER BY createdAt DESC")
        LiveData<List<String>> bookmarks(String uid);

        @Query("SELECT COUNT(*) FROM bookmarks WHERE uid = :uid AND driveId = :driveId")
        int bookmarkCount(String uid, String driveId);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void addBookmark(Bookmark b);

        @Query("DELETE FROM bookmarks WHERE uid = :uid AND driveId = :driveId")
        void removeBookmark(String uid, String driveId);

        @Query("SELECT * FROM readiness_activities WHERE uid = :uid ORDER BY date DESC, createdAt DESC")
        LiveData<List<ActivityRow>> activities(String uid);

        @Query("SELECT * FROM readiness_activities WHERE uid = :uid AND synced = 0")
        List<ActivityRow> unsynced(String uid);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void upsertActivity(ActivityRow row);

        @Query("UPDATE readiness_activities SET synced = 1 WHERE id = :id")
        void markSynced(String id);

        @Query("SELECT * FROM cached_drives")
        List<CachedDrive> cachedDrives();

        @Query("DELETE FROM cached_drives")
        void clearDrives();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void putDrives(List<CachedDrive> drives);

        @Transaction
        default void replaceDrives(List<CachedDrive> drives) {
            clearDrives();
            putDrives(drives);
        }

        @Query("SELECT * FROM cached_applications WHERE uid = :uid")
        List<CachedApplication> cachedApplications(String uid);

        @Query("DELETE FROM cached_applications WHERE uid = :uid")
        void clearApplications(String uid);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void putApplications(List<CachedApplication> apps);

        @Transaction
        default void replaceApplications(String uid, List<CachedApplication> apps) {
            clearApplications(uid);
            putApplications(apps);
        }
    }

    public abstract Store store();

    private static volatile LocalDb instance;

    public static LocalDb get(Context context) {
        if (instance == null) {
            synchronized (LocalDb.class) {
                if (instance == null) {
                    Builder<LocalDb> b = Room.databaseBuilder(context.getApplicationContext(), LocalDb.class, "nexora.db");
                    // Destructive migration is acceptable only for debug builds; release builds must ship migrations.
                    if (BuildConfig.DEBUG) b.fallbackToDestructiveMigration(true);
                    instance = b.build();
                }
            }
        }
        return instance;
    }
}
