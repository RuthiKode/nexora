package com.nexora.data;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.nexora.NexoraApp;
import com.nexora.R;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Notice;
import com.nexora.ui.AuthActivity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One background worker for both sync jobs (no server, so no real push):
 * 1. uploads unsynced readiness activities, 2. fetches new notifications and posts local notifications.
 * Runs every 15 minutes on network, and once on demand after an activity is logged offline.
 */
public class SyncWorker extends Worker {
    public static final String CHANNEL_UPDATES = "updates", CHANNEL_ANNOUNCEMENTS = "announcements";
    public static final String EXTRA_OPEN = "open";
    private static final String PREFS = "nexora", PERIODIC = "nexora-sync", ONCE = "nexora-sync-now";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        NexoraApp.AppContainer c = NexoraApp.container(getApplicationContext());
        String uid = c.auth.uid();
        if (uid == null || !Models.ROLE_STUDENT.equals(prefs(getApplicationContext()).getString("role", null))) {
            return Result.success();
        }
        try {
            c.readiness.syncNow(uid);
            fetchNotices(getApplicationContext(), c.firestore, uid);
            return Result.success();
        } catch (Exception e) {
            return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
        }
    }

    private static void fetchNotices(Context ctx, FirebaseFirestore db, String uid) throws Exception {
        long since = prefs(ctx).getLong(seenKey(uid), 0);
        if (since == 0) { // first run: start from now instead of replaying history
            prefs(ctx).edit().putLong(seenKey(uid), System.currentTimeMillis()).apply();
            return;
        }
        Timestamp after = new Timestamp(new Date(since));
        CollectionReference col = db.collection(FirestoreMapper.NOTIFICATIONS);
        List<Notice> found = new ArrayList<>();
        found.addAll(FirestoreMapper.parse(Tasks.await(col.whereEqualTo("audience", Notice.ALL)
                .whereGreaterThan("createdAt", after).orderBy("createdAt", Query.Direction.DESCENDING).get()), FirestoreMapper::notice));
        found.addAll(FirestoreMapper.parse(Tasks.await(col.whereEqualTo("targetUid", uid)
                .whereGreaterThan("createdAt", after).orderBy("createdAt", Query.Direction.DESCENDING).get()), FirestoreMapper::notice));
        deliver(ctx, uid, found);
    }

    // ---------------------------------------------------------------- scheduling

    public static void schedule(Context ctx) {
        Constraints net = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES).setConstraints(net).build());
    }

    /** Runs as soon as the device is online (e.g. after logging an activity while offline). */
    public static void syncSoon(Context ctx) {
        Constraints net = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(net).build());
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String seenKey(String uid) { return "lastNoticeAt_" + uid; }

    // ---------------------------------------------------------------- local notifications

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_UPDATES,
                ctx.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ANNOUNCEMENTS,
                ctx.getString(R.string.channel_announcements), NotificationManager.IMPORTANCE_DEFAULT));
    }

    /**
     * Posts notices newer than the last one delivered to this user. Shared by the worker and the
     * in-app live listener so each notice is shown once.
     */
    public static synchronized void deliver(Context ctx, String uid, List<Notice> notices) {
        long seen = prefs(ctx).getLong(seenKey(uid), 0);
        if (seen == 0) {
            prefs(ctx).edit().putLong(seenKey(uid), System.currentTimeMillis()).apply();
            return;
        }
        long max = seen;
        for (Notice n : notices) {
            if (n.createdAt == null || n.createdAt.getTime() <= seen) continue;
            show(ctx, n.id.hashCode(), n.title, n.body, Notice.STUDENT.equals(n.audience) ? CHANNEL_UPDATES : CHANNEL_ANNOUNCEMENTS);
            max = Math.max(max, n.createdAt.getTime());
        }
        if (max > seen) prefs(ctx).edit().putLong(seenKey(uid), max).apply();
    }

    public static void show(Context ctx, int id, String title, String body, String channel) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent open = new Intent(ctx, AuthActivity.class).putExtra(EXTRA_OPEN, "notifications")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationManagerCompat.from(ctx).notify(id, new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notifications)
                .setColor(ContextCompat.getColor(ctx, R.color.nx_brand))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build());
    }
}
