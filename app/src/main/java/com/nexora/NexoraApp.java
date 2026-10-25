package com.nexora;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;
import com.nexora.data.AuthRepository;
import com.nexora.data.LocalDb;
import com.nexora.data.PlacementRepository;
import com.nexora.data.ReadinessRepository;
import com.nexora.data.SyncWorker;
import com.nexora.domain.Analytics;
import com.nexora.domain.ApplicationWorkflow;
import com.nexora.domain.EligibilityEngine;
import com.nexora.domain.MatchScoreCalculator;
import com.nexora.domain.ReadinessAnalyzer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application: initialises Firebase (emulator in debug), builds the manual DI container, schedules sync. */
public class NexoraApp extends Application {

    /** Manual dependency container — one instance per process, no DI framework. */
    public static final class AppContainer {
        public final FirebaseFirestore firestore;
        public final ExecutorService io = Executors.newSingleThreadExecutor();
        public final AuthRepository auth;
        public final PlacementRepository placement;
        public final ReadinessRepository readiness;
        public final EligibilityEngine eligibility = new EligibilityEngine();
        public final MatchScoreCalculator match = new MatchScoreCalculator();
        public final ReadinessAnalyzer readinessAnalyzer = new ReadinessAnalyzer();
        public final ApplicationWorkflow workflow = new ApplicationWorkflow();
        public final Analytics analytics = new Analytics();

        AppContainer(Context ctx) {
            firestore = FirebaseFirestore.getInstance();
            LocalDb.Store store = LocalDb.get(ctx).store();
            auth = new AuthRepository(FirebaseAuth.getInstance(), firestore);
            placement = new PlacementRepository(firestore, store, io);
            readiness = new ReadinessRepository(firestore, store, io);
        }
    }

    private AppContainer container;

    public static AppContainer container(Context ctx) {
        return ((NexoraApp) ctx.getApplicationContext()).container;
    }

    /** True when running against the offline demo project (no google-services.json supplied). */
    public static boolean isDemoProject() {
        return BuildConfig.FIREBASE_PROJECTID.startsWith("demo-");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(SyncWorker.prefs(this)
                .getInt("nightMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        initFirebase();
        container = new AppContainer(this);
        SyncWorker.createChannels(this);
        SyncWorker.schedule(this);
    }

    private void initFirebase() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, new FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECTID)
                    .setApiKey(BuildConfig.FIREBASE_APIKEY)
                    .setApplicationId(BuildConfig.FIREBASE_APPID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDERID)
                    .build());
        }
        if (BuildConfig.USE_EMULATOR) {
            FirebaseFirestore.getInstance().useEmulator(BuildConfig.EMULATOR_HOST, 8080);
            FirebaseAuth.getInstance().useEmulator(BuildConfig.EMULATOR_HOST, 9099);
        }
        // FCM needs a real project; it is disabled in the manifest and only enabled here when configured.
        if (!isDemoProject()) FirebaseMessaging.getInstance().setAutoInitEnabled(true);
    }
}
