package com.nexora.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;
import com.nexora.NexoraApp;
import com.nexora.R;
import com.nexora.data.Resource;
import com.nexora.data.SyncWorker;
import com.nexora.domain.Validators;

import java.util.Arrays;
import java.util.List;

/** Splash routing, first-run onboarding, sign in, registration (always STUDENT) and password reset. */
public class AuthActivity extends AppCompatActivity {
    private NexoraApp.AppContainer c;
    private View routing, onboarding, authContent;
    private TextInputLayout nameL, emailL, passwordL;
    private Button submit, forgot;
    private View progress;
    private boolean registerMode;

    private static final int[] SLIDE_ICONS = {R.drawable.ic_verified, R.drawable.ic_trending, R.drawable.ic_school};
    private static final String[][] SLIDE_TEXT = {
            {"Know where you stand", "See exactly which eligibility criteria you meet for every drive — and why you don't meet the rest."},
            {"Find your best matches", "An explainable match score ranks drives by your skills, role preferences and preparation."},
            {"Prepare with purpose", "Log practice, track your readiness, and get suggestions linked to drives closing soon."}};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_NEXORA);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_auth);
        c = NexoraApp.container(this);
        routing = findViewById(R.id.routing);
        onboarding = findViewById(R.id.onboarding);
        authContent = findViewById(R.id.auth_content);
        View hero = findViewById(R.id.hero);
        int heroTop = hero.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.auth_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            onboarding.setPadding(0, bars.top, 0, 0);
            hero.setPadding(hero.getPaddingLeft(), heroTop + bars.top, hero.getPaddingRight(), hero.getPaddingBottom());
            return insets;
        });
        setupAuthForm();

        if (c.auth.uid() != null) route();
        else if (!prefs().getBoolean("onboarded", false)) showOnboarding();
        else show(authContent);
    }

    private SharedPreferences prefs() { return SyncWorker.prefs(this); }

    private void show(View v) {
        for (View x : new View[]{routing, onboarding, authContent}) x.setVisibility(x == v ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- onboarding

    private void showOnboarding() {
        show(onboarding);
        ViewPager2 pager = findViewById(R.id.pager);
        List<Integer> pages = Arrays.asList(0, 1, 2);
        UiKit.Adapter<Integer> adapter = new UiKit.Adapter<>(R.layout.item_onboarding, (v, i, pos) -> {
            ((ImageView) v.findViewById(R.id.slide_icon)).setImageResource(SLIDE_ICONS[i]);
            ((TextView) v.findViewById(R.id.slide_title)).setText(SLIDE_TEXT[i][0]);
            ((TextView) v.findViewById(R.id.slide_body)).setText(SLIDE_TEXT[i][1]);
        });
        pager.setAdapter(adapter);
        adapter.submit(pages);
        LinearLayout dots = findViewById(R.id.dots);
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiKit.dp(this, 8), UiKit.dp(this, 8));
            lp.setMargins(UiKit.dp(this, 4), 0, UiKit.dp(this, 4), 0);
            dot.setBackgroundResource(R.drawable.bg_circle);
            dots.addView(dot, lp);
        }
        Button next = findViewById(R.id.next);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < dots.getChildCount(); i++) {
                    dots.getChildAt(i).setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(dots,
                            i == position ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOutlineVariant)));
                }
                next.setText(position == pages.size() - 1 ? "Get started" : getString(R.string.next));
            }
        });
        next.setOnClickListener(v -> {
            if (pager.getCurrentItem() < pages.size() - 1) pager.setCurrentItem(pager.getCurrentItem() + 1);
            else finishOnboarding();
        });
        findViewById(R.id.skip).setOnClickListener(v -> finishOnboarding());
    }

    private void finishOnboarding() {
        prefs().edit().putBoolean("onboarded", true).apply();
        show(authContent);
    }

    // ---------------------------------------------------------------- sign in / register

    private void setupAuthForm() {
        nameL = findViewById(R.id.name_layout);
        emailL = findViewById(R.id.email_layout);
        passwordL = findViewById(R.id.password_layout);
        submit = findViewById(R.id.submit);
        forgot = findViewById(R.id.forgot);
        progress = findViewById(R.id.auth_progress);
        MaterialButtonToggleGroup mode = findViewById(R.id.mode);
        mode.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            registerMode = id == R.id.mode_register;
            nameL.setVisibility(registerMode ? View.VISIBLE : View.GONE);
            forgot.setVisibility(registerMode ? View.GONE : View.VISIBLE);
            submit.setText(registerMode ? R.string.create_account : R.string.sign_in);
            ((TextView) findViewById(R.id.auth_title)).setText(registerMode ? "Create your account" : getString(R.string.welcome_back));
            ((TextView) findViewById(R.id.auth_subtitle)).setText(registerMode
                    ? "Student accounts only. Your academic record is verified by the placement office."
                    : getString(R.string.sign_in_subtitle));
            passwordL.setHelperText(registerMode ? "At least 8 characters" : null);
        });
        submit.setOnClickListener(v -> submit());
        if (passwordL.getEditText() != null) {
            passwordL.getEditText().setOnEditorActionListener((tv, action, ev) -> { submit(); return true; });
        }
        forgot.setOnClickListener(v -> UiKit.form(this, "Reset password",
                "We'll email you a link to set a new password.", new String[]{getString(R.string.email)},
                new String[]{UiKit.text(emailL)}, new int[]{InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_CLASS_TEXT},
                "Send link", (vals, f) -> {
                    if (!UiKit.check(f[0], Validators.email(vals[0]))) return false;
                    c.auth.resetPassword(vals[0], r -> UiKit.snack(authContent, r.isSuccess()
                            ? "Reset link sent to " + vals[0] : r.message));
                    return true;
                }));
    }

    private void submit() {
        String email = UiKit.text(emailL), pw = passwordL.getEditText() == null ? "" : passwordL.getEditText().getText().toString();
        boolean ok = UiKit.check(emailL, Validators.email(email));
        ok &= UiKit.check(passwordL, registerMode ? Validators.password(pw) : (pw.isEmpty() ? "Enter your password" : null));
        if (registerMode) ok &= UiKit.check(nameL, Validators.name(UiKit.text(nameL)));
        if (!ok) return;
        busy(true);
        Resource.Callback<String> done = r -> {
            busy(false);
            if (r.isSuccess()) open(r.data);
            else UiKit.snack(authContent, r.message);
        };
        if (registerMode) c.auth.register(UiKit.text(nameL), email, pw, done);
        else c.auth.signIn(email, pw, done);
    }

    private void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        submit.setEnabled(!b);
    }

    // ---------------------------------------------------------------- routing

    /**
     * Role comes from users/{uid} on the server. A cached role for the same uid opens the app at once
     * (and offline); MainActivity then re-checks the server role and restarts if it changed.
     */
    private void route() {
        show(routing);
        String cached = prefs().getString("role", null);
        if (cached != null && c.auth.uid().equals(prefs().getString("roleUid", null))) {
            open(cached);
            return;
        }
        TextView msg = findViewById(R.id.routing_message);
        View retry = findViewById(R.id.routing_retry);
        msg.setText(null);
        retry.setVisibility(View.GONE);
        c.auth.loadRole(r -> {
            if (r.isSuccess()) {
                open(r.data);
            } else {
                msg.setText(r.message);
                retry.setVisibility(View.VISIBLE);
                retry.setOnClickListener(v -> route());
            }
        });
    }

    private void open(String role) {
        prefs().edit().putString("role", role).putString("roleUid", c.auth.uid()).apply();
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_ROLE, role);
        if (getIntent().getStringExtra(SyncWorker.EXTRA_OPEN) != null) {
            i.putExtra(SyncWorker.EXTRA_OPEN, getIntent().getStringExtra(SyncWorker.EXTRA_OPEN));
        }
        startActivity(i);
        finish();
    }
}
