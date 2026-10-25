package com.nexora.data;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.google.android.gms.tasks.Task;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Company;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Companies, drives, applications, notifications and bookmarks. Drives and applications are
 * written through to Room so they stay browsable offline even when the Firestore cache is empty.
 */
public final class PlacementRepository {
    private final FirebaseFirestore db;
    private final LocalDb.Store store;
    private final Executor io;

    public PlacementRepository(FirebaseFirestore db, LocalDb.Store store, Executor io) {
        this.db = db;
        this.store = store;
        this.io = io;
    }

    // ---------------------------------------------------------------- companies

    public LiveData<Resource<List<Company>>> companies() {
        return new FirestoreMapper.QueryLiveData<>(db.collection(FirestoreMapper.COMPANIES).orderBy("name"),
                FirestoreMapper::company);
    }

    public void saveCompany(Company c, String officerUid, Resource.Callback<Void> cb) {
        Map<String, Object> m = FirestoreMapper.companyMap(c);
        CollectionReference col = db.collection(FirestoreMapper.COMPANIES);
        DocumentReference ref = c.id == null ? col.document() : col.document(c.id);
        if (c.id == null) {
            m.put("createdBy", officerUid);
            m.put("createdAt", FieldValue.serverTimestamp());
        }
        complete(ref.set(m, SetOptions.merge()), cb);
    }

    // ---------------------------------------------------------------- drives

    /** Student view: rules hide DRAFT drives, so the query must ask only for OPEN and CLOSED. */
    public LiveData<Resource<List<Drive>>> studentDrives() {
        Query q = db.collection(FirestoreMapper.DRIVES)
                .whereIn("status", Arrays.asList(Models.DriveStatus.OPEN.name(), Models.DriveStatus.CLOSED.name()));
        return cached(new FirestoreMapper.QueryLiveData<>(q, FirestoreMapper::drive),
                () -> {
                    List<Drive> out = new ArrayList<>();
                    for (LocalDb.CachedDrive c : store.cachedDrives()) out.add(FirestoreMapper.drive(c.id, FirestoreMapper.fromJson(c.json)));
                    return out;
                },
                drives -> {
                    List<LocalDb.CachedDrive> rows = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    for (Drive d : drives) {
                        LocalDb.CachedDrive row = new LocalDb.CachedDrive();
                        row.id = d.id;
                        row.json = FirestoreMapper.toJson(withDates(FirestoreMapper.driveMap(d), d.createdAt, d.updatedAt));
                        row.cachedAt = now;
                        rows.add(row);
                    }
                    store.replaceDrives(rows);
                },
                Comparator.comparing((Drive d) -> d.deadline == null ? Long.MAX_VALUE : d.deadline.getTime()));
    }

    /** Officer view: every drive including drafts. */
    public LiveData<Resource<List<Drive>>> allDrives() {
        return new FirestoreMapper.QueryLiveData<>(
                db.collection(FirestoreMapper.DRIVES).orderBy("deadline", Query.Direction.ASCENDING), FirestoreMapper::drive);
    }

    public void saveDrive(Drive d, String officerUid, Resource.Callback<String> cb) {
        Map<String, Object> m = FirestoreMapper.driveMap(d);
        m.put("updatedAt", FieldValue.serverTimestamp());
        CollectionReference col = db.collection(FirestoreMapper.DRIVES);
        DocumentReference ref = d.id == null ? col.document() : col.document(d.id);
        if (d.id == null) {
            m.put("createdBy", officerUid);
            m.put("createdAt", FieldValue.serverTimestamp());
        }
        ref.set(m, SetOptions.merge())
                .addOnSuccessListener(v -> cb.onResult(Resource.success(ref.getId())))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }

    public void setDriveStatus(String driveId, Models.DriveStatus status, Resource.Callback<Void> cb) {
        complete(db.collection(FirestoreMapper.DRIVES).document(driveId)
                .update("status", status.name(), "updatedAt", FieldValue.serverTimestamp()), cb);
    }

    /** applicantCount is display-only and maintained by the officer client when reviewing applicants. */
    public void syncApplicantCount(String driveId, int count) {
        db.collection(FirestoreMapper.DRIVES).document(driveId).update("applicantCount", count);
    }

    // ---------------------------------------------------------------- applications

    public LiveData<Resource<List<Application>>> myApplications(String uid) {
        Query q = db.collection(FirestoreMapper.APPLICATIONS).whereEqualTo("studentId", uid);
        return cached(new FirestoreMapper.QueryLiveData<>(q, FirestoreMapper::application),
                () -> {
                    List<Application> out = new ArrayList<>();
                    for (LocalDb.CachedApplication c : store.cachedApplications(uid)) {
                        out.add(FirestoreMapper.application(c.id, FirestoreMapper.fromJson(c.json)));
                    }
                    return out;
                },
                apps -> {
                    List<LocalDb.CachedApplication> rows = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    for (Application a : apps) {
                        LocalDb.CachedApplication row = new LocalDb.CachedApplication();
                        row.id = a.id;
                        row.uid = uid;
                        Map<String, Object> m = FirestoreMapper.applicationMap(a);
                        m.put("appliedAt", a.appliedAt);
                        m.put("updatedAt", a.updatedAt);
                        row.json = FirestoreMapper.toJson(m);
                        row.cachedAt = now;
                        rows.add(row);
                    }
                    store.replaceApplications(uid, rows);
                },
                Comparator.comparing((Application a) -> a.updatedAt == null ? 0L : -a.updatedAt.getTime()));
    }

    /** Officer only: all applications (client-side filtering per drive; single-campus scale). */
    public LiveData<Resource<List<Application>>> allApplications() {
        return new FirestoreMapper.QueryLiveData<>(db.collection(FirestoreMapper.APPLICATIONS)
                .orderBy("appliedAt", Query.Direction.ASCENDING), FirestoreMapper::application);
    }

    /**
     * Creates the application with the deterministic id {driveId}_{uid}. A second attempt is an
     * update, which rules deny for students, so duplicates are rejected by the server.
     */
    public void apply(Application a, Resource.Callback<Void> cb) {
        Map<String, Object> m = FirestoreMapper.applicationMap(a);
        m.put("appliedAt", FieldValue.serverTimestamp());
        m.put("updatedAt", FieldValue.serverTimestamp());
        db.collection(FirestoreMapper.APPLICATIONS).document(a.id).set(m)
                .addOnSuccessListener(v -> cb.onResult(Resource.success(null)))
                .addOnFailureListener(e -> {
                    boolean denied = e instanceof FirebaseFirestoreException
                            && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED;
                    cb.onResult(Resource.error(denied
                            ? "The server rejected this application. You may have already applied, the drive may be closed or past its deadline, or your verified record does not meet the criteria."
                            : FirestoreMapper.errorMessage(e), null));
                });
    }

    /** Officer decision: the application update and the student's notification are one atomic batch. */
    public void updateApplication(Application updated, Notice notice, Resource.Callback<Void> cb) {
        WriteBatch batch = db.batch();
        Map<String, Object> m = new HashMap<>();
        m.put("status", updated.status);
        m.put("currentStage", updated.currentStage);
        m.put("stageHistory", FirestoreMapper.historyList(updated.stageHistory));
        m.put("updatedAt", FieldValue.serverTimestamp());
        batch.update(db.collection(FirestoreMapper.APPLICATIONS).document(updated.id), m);
        Map<String, Object> n = FirestoreMapper.noticeMap(notice);
        n.put("createdAt", FieldValue.serverTimestamp());
        batch.set(db.collection(FirestoreMapper.NOTIFICATIONS).document(), n);
        complete(batch.commit(), cb);
    }

    // ---------------------------------------------------------------- notifications

    /** Students: two rule-compatible queries (broadcasts + personal updates) merged client-side. */
    public LiveData<Resource<List<Notice>>> studentNotices(String uid) {
        CollectionReference col = db.collection(FirestoreMapper.NOTIFICATIONS);
        LiveData<Resource<List<Notice>>> all = new FirestoreMapper.QueryLiveData<>(
                col.whereEqualTo("audience", Notice.ALL).orderBy("createdAt", Query.Direction.DESCENDING).limit(50),
                FirestoreMapper::notice);
        LiveData<Resource<List<Notice>>> mine = new FirestoreMapper.QueryLiveData<>(
                col.whereEqualTo("targetUid", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(50),
                FirestoreMapper::notice);
        MediatorLiveData<Resource<List<Notice>>> out = new MediatorLiveData<>();
        Runnable merge = () -> {
            Resource<List<Notice>> a = all.getValue(), b = mine.getValue();
            if (a == null || b == null) return;
            if (a.isError() || b.isError()) {
                out.setValue(Resource.error(a.isError() ? a.message : b.message, mergeNotices(a.data, b.data)));
            } else if (a.isLoading() || b.isLoading()) {
                out.setValue(Resource.loading(null));
            } else {
                out.setValue(Resource.success(mergeNotices(a.data, b.data), a.fromCache || b.fromCache));
            }
        };
        out.addSource(all, r -> merge.run());
        out.addSource(mine, r -> merge.run());
        return out;
    }

    static List<Notice> mergeNotices(List<Notice> a, List<Notice> b) {
        Map<String, Notice> byId = new LinkedHashMap<>();
        if (a != null) for (Notice n : a) byId.put(n.id, n);
        if (b != null) for (Notice n : b) byId.put(n.id, n);
        List<Notice> out = new ArrayList<>(byId.values());
        out.sort((x, y) -> Long.compare(y.createdAt == null ? Long.MAX_VALUE : y.createdAt.getTime(),
                x.createdAt == null ? Long.MAX_VALUE : x.createdAt.getTime()));
        return out;
    }

    public LiveData<Resource<List<Notice>>> allNotices() {
        return new FirestoreMapper.QueryLiveData<>(db.collection(FirestoreMapper.NOTIFICATIONS)
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100), FirestoreMapper::notice);
    }

    public void publishAnnouncement(String title, String body, String officerUid, Resource.Callback<Void> cb) {
        Notice n = new Notice();
        n.title = title;
        n.body = body;
        n.audience = Notice.ALL;
        n.createdBy = officerUid;
        Map<String, Object> m = FirestoreMapper.noticeMap(n);
        m.put("createdAt", FieldValue.serverTimestamp());
        complete(db.collection(FirestoreMapper.NOTIFICATIONS).document().set(m), cb);
    }

    // ---------------------------------------------------------------- bookmarks (Room, local-first)

    public LiveData<List<String>> bookmarks(String uid) { return store.bookmarks(uid); }

    public void toggleBookmark(String uid, String driveId) {
        io.execute(() -> {
            if (store.bookmarkCount(uid, driveId) > 0) {
                store.removeBookmark(uid, driveId);
            } else {
                LocalDb.Bookmark b = new LocalDb.Bookmark();
                b.uid = uid;
                b.driveId = driveId;
                b.createdAt = System.currentTimeMillis();
                store.addBookmark(b);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private interface Loader<T> { List<T> load(); }
    private interface Saver<T> { void save(List<T> items); }

    /**
     * Wraps a live query with the Room cache: cached rows are shown first, server results replace
     * and refresh the cache, and on error or an empty offline snapshot the cached rows are kept.
     */
    private <T> LiveData<Resource<List<T>>> cached(LiveData<Resource<List<T>>> live, Loader<T> loader,
                                                  Saver<T> saver, Comparator<T> order) {
        MediatorLiveData<Resource<List<T>>> out = new MediatorLiveData<>();
        out.setValue(Resource.loading(null));
        // Both fields are only touched on the main thread.
        final List<List<T>> room = new ArrayList<>();
        final boolean[] serverServed = {false};
        io.execute(() -> {
            List<T> rows = loader.load();
            rows.sort(order);
            main.post(() -> {
                room.add(rows);
                if (!serverServed[0] && !rows.isEmpty()) out.setValue(Resource.success(rows, true));
            });
        });
        out.addSource(live, r -> {
            List<T> cachedRows = room.isEmpty() ? new ArrayList<>() : room.get(0);
            if (r.isSuccess() && !(r.fromCache && r.data.isEmpty() && !cachedRows.isEmpty())) {
                serverServed[0] = !r.fromCache;
                List<T> sorted = new ArrayList<>(r.data);
                sorted.sort(order);
                out.setValue(Resource.success(sorted, r.fromCache));
                if (!r.fromCache) io.execute(() -> saver.save(sorted));
            } else if (r.isError()) {
                out.setValue(Resource.error(r.message, cachedRows.isEmpty() ? null : cachedRows));
            } else if (r.isSuccess()) {
                out.setValue(Resource.success(cachedRows, true));
            }
        });
        return out;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private static Map<String, Object> withDates(Map<String, Object> m, Date createdAt, Date updatedAt) {
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }

    private static void complete(Task<Void> task, Resource.Callback<Void> cb) {
        task.addOnSuccessListener(v -> cb.onResult(Resource.success(null)))
                .addOnFailureListener(e -> cb.onResult(Resource.error(FirestoreMapper.errorMessage(e), null)));
    }
}
