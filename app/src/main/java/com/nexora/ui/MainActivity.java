package com.nexora.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.messaging.FirebaseMessaging;
import com.nexora.NexoraApp;
import com.nexora.R;
import com.nexora.data.SyncWorker;
import com.nexora.domain.Models;

/**
 * One shell for both portals. The bottom navigation menu and root screens depend on the role read
 * from users/{uid} by AuthActivity; data access is still enforced by Firestore rules.
 */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_ROLE = "role";

    private String role;
    private BottomNavigationView nav;
    private View offlineBanner;
    public final MutableLiveData<Boolean> online = new MutableLiveData<>(true);
    private ConnectivityManager.NetworkCallback netCallback;

    public static MainActivity of(Fragment f) { return (MainActivity) f.requireActivity(); }

    public boolean isStudent() { return Models.ROLE_STUDENT.equals(role); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NexoraApp.AppContainer c = NexoraApp.container(this);
        role = getIntent().getStringExtra(EXTRA_ROLE);
        if (c.auth.uid() == null || role == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish();
            return;
        }
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.main_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, ime.bottom);
            nav.setVisibility(ime.bottom > 0 ? View.GONE : View.VISIBLE);
            return insets;
        });
        setSupportActionBar(findViewById(R.id.toolbar));
        offlineBanner = findViewById(R.id.offline_banner);
        nav = findViewById(R.id.bottom_nav);
        nav.inflateMenu(isStudent() ? R.menu.menu_student_nav : R.menu.menu_officer_nav);
        nav.setOnItemSelectedListener(item -> { showRoot(item.getItemId()); return true; });
        nav.setOnItemReselectedListener(item -> getSupportFragmentManager()
                .popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE));
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateUp);
        ((androidx.appcompat.widget.Toolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        if (savedInstanceState == null) {
            // The first item is pre-selected, so setSelectedItemId would not fire the listener.
            showRoot(isStudent() ? R.id.nav_home : R.id.nav_dashboard);
            if (isStudent() && "notifications".equals(getIntent().getStringExtra(SyncWorker.EXTRA_OPEN))) {
                push(new StudentScreens.NotificationsFragment());
            }
        }
        watchConnectivity();
        if (isStudent()) setupStudent(c);
        // Re-verify the role against the server (the launch may have used the cached role).
        c.auth.loadRole(r -> {
            if (r.isSuccess() && !r.data.equals(role) && !isFinishing()) {
                SyncWorker.prefs(this).edit().putString("role", r.data).apply();
                startActivity(new Intent(this, AuthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }
        });
    }

    private void setupStudent(NexoraApp.AppContainer c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        StudentViewModel vm = new ViewModelProvider(this).get(StudentViewModel.class);
        // Live listener while the app is open → local notifications (deduplicated with the worker).
        vm.notices.observe(this, r -> {
            if (r != null && r.isSuccess() && !r.fromCache && r.data != null) SyncWorker.deliver(this, vm.uid, r.data);
        });
        c.io.execute(() -> {
            try { c.readiness.pullRemote(vm.uid); } catch (Exception ignored) { /* offline: Room data stays */ }
        });
        SyncWorker.syncSoon(this);
        if (!NexoraApp.isDemoProject()) {
            FirebaseMessaging.getInstance().subscribeToTopic("all_students");
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(c.auth::saveFcmToken);
        }
    }

    private void watchConnectivity() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        setOnline(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) { runOnUiThread(() -> setOnline(true)); }
            @Override public void onLost(@NonNull Network n) { runOnUiThread(() -> setOnline(false)); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void setOnline(boolean on) {
        online.setValue(on);
        offlineBanner.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    public boolean isOnline() { return Boolean.TRUE.equals(online.getValue()); }

    @Override
    protected void onDestroy() {
        if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- navigation

    private void showRoot(int id) {
        Fragment f;
        if (id == R.id.nav_home) f = new StudentScreens.HomeFragment();
        else if (id == R.id.nav_drives) f = new DriveScreens.DrivesFragment();
        else if (id == R.id.nav_applications) f = new StudentScreens.ApplicationsFragment();
        else if (id == R.id.nav_readiness) f = new ProfileScreens.ReadinessFragment();
        else if (id == R.id.nav_profile) f = new ProfileScreens.ProfileFragment();
        else if (id == R.id.nav_dashboard) f = new OfficerScreens.DashboardFragment();
        else if (id == R.id.nav_verify) f = new OfficerScreens.VerifyFragment();
        else if (id == R.id.nav_officer_drives) f = new DriveAdminScreens.DrivesFragment();
        else if (id == R.id.nav_announce) f = new OfficerScreens.AnnouncementsFragment();
        else f = new OfficerScreens.AnalyticsFragment();
        FragmentManager fm = getSupportFragmentManager();
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        fm.beginTransaction().setReorderingAllowed(true).replace(R.id.container, f).commit();
    }

    /** Opens a detail screen on top of the current tab. */
    public void push(Fragment f) {
        getSupportFragmentManager().beginTransaction().setReorderingAllowed(true)
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.container, f).addToBackStack(null).commit();
    }

    public void selectTab(int id) { nav.setSelectedItemId(id); }

    private void updateUp() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        menu.findItem(R.id.action_notifications).setVisible(isStudent());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_notifications) {
            push(new StudentScreens.NotificationsFragment());
        } else if (id == R.id.action_theme) {
            String[] modes = {"System default", "Light", "Dark"};
            int[] values = {AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES};
            int current = SyncWorker.prefs(this).getInt("nightMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            int checked = current == values[1] ? 1 : current == values[2] ? 2 : 0;
            new MaterialAlertDialogBuilder(this).setTitle(R.string.theme).setSingleChoiceItems(modes, checked, (d, w) -> {
                SyncWorker.prefs(this).edit().putInt("nightMode", values[w]).apply();
                AppCompatDelegate.setDefaultNightMode(values[w]);
                d.dismiss();
            }).show();
        } else if (id == R.id.action_logout) {
            UiKit.confirm(this, "Log out?", "You can sign back in at any time. Unsynced activity stays on this device.",
                    getString(R.string.logout), () -> {
                        NexoraApp.container(this).auth.signOut();
                        SyncWorker.prefs(this).edit().remove("role").remove("roleUid").apply();
                        startActivity(new Intent(this, AuthActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        finish();
                    });
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }
}
