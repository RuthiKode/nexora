package com.nexora.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.nexora.domain.Models.Activity;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Readiness activities are local-first: they are written to Room immediately (works offline) and
 * uploaded by {@link SyncWorker}. The Firestore document id is the Room UUID, so uploads are idempotent.
 */
public final class ReadinessRepository {
    private final FirebaseFirestore db;
    private final LocalDb.Store store;
    private final Executor io;

    public ReadinessRepository(FirebaseFirestore db, LocalDb.Store store, Executor io) {
        this.db = db;
        this.store = store;
        this.io = io;
    }

    public LiveData<List<Activity>> activities(String uid) {
        return Transformations.map(store.activities(uid), rows -> {
            List<Activity> out = new ArrayList<>();
            for (LocalDb.ActivityRow r : rows) out.add(toModel(r));
            return out;
        });
    }

    /** Saves locally (assigning a UUID) and returns immediately; the caller schedules a sync. */
    public void log(String uid, Activity a, Runnable onSaved) {
        if (a.id == null) a.id = UUID.randomUUID().toString();
        if (a.createdAt == null) a.createdAt = new Date();
        LocalDb.ActivityRow row = toRow(uid, a, false);
        io.execute(() -> {
            store.upsertActivity(row);
            if (onSaved != null) onSaved.run();
        });
    }

    private CollectionReference remote(String uid) {
        return db.collection(FirestoreMapper.READINESS).document(uid).collection(FirestoreMapper.ACTIVITIES);
    }

    /** Blocking (worker thread): uploads unsynced rows with set(), so a retried upload never duplicates. */
    public int syncNow(String uid) throws ExecutionException, InterruptedException {
        int uploaded = 0;
        for (LocalDb.ActivityRow row : store.unsynced(uid)) {
            Tasks.await(remote(uid).document(row.id).set(FirestoreMapper.activityMap(toModel(row))));
            store.markSynced(row.id);
            uploaded++;
        }
        return uploaded;
    }

    /** Blocking: pulls remote activities into Room after login (e.g. on a new device). */
    public void pullRemote(String uid) throws ExecutionException, InterruptedException {
        QuerySnapshot snap = Tasks.await(remote(uid).get());
        for (DocumentSnapshot d : snap.getDocuments()) {
            Map<String, Object> data = d.getData();
            if (data == null) continue;
            store.upsertActivity(toRow(uid, FirestoreMapper.activity(d.getId(), data), true));
        }
    }

    /** Officer analytics: activities of several students, keyed by uid. */
    public Task<Map<String, List<Activity>>> fetchFor(List<String> uids) {
        List<Task<QuerySnapshot>> tasks = new ArrayList<>();
        for (String uid : uids) tasks.add(remote(uid).get());
        return Tasks.whenAllComplete(tasks).continueWith(t -> {
            Map<String, List<Activity>> out = new HashMap<>();
            for (int i = 0; i < uids.size(); i++) {
                Task<QuerySnapshot> q = tasks.get(i);
                out.put(uids.get(i), q.isSuccessful() ? FirestoreMapper.parse(q.getResult(), FirestoreMapper::activity) : new ArrayList<>());
            }
            return out;
        });
    }

    static Activity toModel(LocalDb.ActivityRow r) {
        Activity a = new Activity();
        a.id = r.id;
        a.type = r.type;
        a.title = r.title;
        a.scorePercent = r.scorePercent;
        a.rating = r.rating;
        a.durationMin = r.durationMin;
        a.date = new Date(r.date);
        a.createdAt = new Date(r.createdAt);
        return a;
    }

    static LocalDb.ActivityRow toRow(String uid, Activity a, boolean synced) {
        LocalDb.ActivityRow r = new LocalDb.ActivityRow();
        r.id = a.id;
        r.uid = uid;
        r.type = a.type;
        r.title = a.title;
        r.scorePercent = a.scorePercent;
        r.rating = a.rating;
        r.durationMin = a.durationMin;
        r.date = a.date == null ? 0 : a.date.getTime();
        r.createdAt = a.createdAt == null ? r.date : a.createdAt.getTime();
        r.synced = synced;
        return r;
    }
}
