package com.nexora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.nexora.data.FirestoreMapper;
import com.nexora.data.LocalDb;
import com.nexora.domain.Models;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Room DAO tests (bookmarks, readiness activities and the drive/application cache). */
@RunWith(AndroidJUnit4.class)
public class LocalDbTest {
    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private LocalDb db;
    private LocalDb.Store store;

    @Before public void open() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, LocalDb.class).allowMainThreadQueries().build();
        store = db.store();
    }

    @After public void close() { db.close(); }

    private static <T> T await(androidx.lifecycle.LiveData<T> live) throws InterruptedException {
        final Object[] out = new Object[1];
        CountDownLatch latch = new CountDownLatch(1);
        live.observeForever(v -> { out[0] = v; latch.countDown(); });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    @Test public void bookmarksArePerUserAndToggle() throws Exception {
        LocalDb.Bookmark b = new LocalDb.Bookmark();
        b.uid = "u1"; b.driveId = "d1"; b.createdAt = 1;
        store.addBookmark(b);
        store.addBookmark(b); // REPLACE: no duplicate
        assertEquals(1, store.bookmarkCount("u1", "d1"));
        assertEquals(0, store.bookmarkCount("u2", "d1"));
        assertEquals(Collections.singletonList("d1"), await(store.bookmarks("u1")));
        store.removeBookmark("u1", "d1");
        assertEquals(0, store.bookmarkCount("u1", "d1"));
    }

    private static LocalDb.ActivityRow row(String id, boolean synced) {
        LocalDb.ActivityRow r = new LocalDb.ActivityRow();
        r.id = id; r.uid = "u1"; r.type = "CODING"; r.title = "Practice"; r.scorePercent = 70;
        r.durationMin = 30; r.date = System.currentTimeMillis(); r.createdAt = r.date; r.synced = synced;
        return r;
    }

    @Test public void activitySyncIsIdempotentByUuid() throws Exception {
        store.upsertActivity(row("a1", false));
        store.upsertActivity(row("a2", false));
        assertEquals(2, store.unsynced("u1").size());
        store.markSynced("a1");
        store.markSynced("a1"); // a retried sync changes nothing
        assertEquals(1, store.unsynced("u1").size());
        // A pull of the same remote activity replaces the row instead of duplicating it.
        store.upsertActivity(row("a1", true));
        assertEquals(2, await(store.activities("u1")).size());
        assertEquals(0, store.unsynced("u2").size());
    }

    @Test public void driveCacheRoundTripsThroughJson() {
        Models.Drive d = new Models.Drive();
        d.id = "d1"; d.title = "SDE Intern"; d.companyName = "Acme"; d.minCgpa = 7.0;
        d.eligibleBranches = new ArrayList<>(Arrays.asList("CSE", "CSE_AI"));
        d.graduationYears = new ArrayList<>(Collections.singletonList(2027));
        d.deadline = new Date(1_800_000_000_000L);
        d.stages = Models.Drive.defaultStages();
        LocalDb.CachedDrive c = new LocalDb.CachedDrive();
        c.id = d.id;
        c.json = FirestoreMapper.toJson(FirestoreMapper.driveMap(d));
        store.replaceDrives(Collections.singletonList(c));
        store.replaceDrives(Collections.singletonList(c));
        List<LocalDb.CachedDrive> rows = store.cachedDrives();
        assertEquals(1, rows.size());
        Models.Drive back = FirestoreMapper.drive(rows.get(0).id, FirestoreMapper.fromJson(rows.get(0).json));
        assertEquals("SDE Intern", back.title);
        assertEquals(7.0, back.minCgpa, 1e-9);
        assertEquals(Arrays.asList("CSE", "CSE_AI"), back.eligibleBranches);
        assertEquals(Collections.singletonList(2027), back.graduationYears);
        assertEquals(d.deadline, back.deadline);
        assertEquals(5, back.stages.size());
    }

    @Test public void applicationCacheIsReplacedPerUser() {
        LocalDb.CachedApplication a = new LocalDb.CachedApplication();
        a.id = "d1_u1"; a.uid = "u1"; a.json = "{}";
        LocalDb.CachedApplication b = new LocalDb.CachedApplication();
        b.id = "d1_u2"; b.uid = "u2"; b.json = "{}";
        store.replaceApplications("u1", Collections.singletonList(a));
        store.replaceApplications("u2", Collections.singletonList(b));
        store.replaceApplications("u1", new ArrayList<>());
        assertEquals(0, store.cachedApplications("u1").size());
        assertEquals(1, store.cachedApplications("u2").size());
    }
}
