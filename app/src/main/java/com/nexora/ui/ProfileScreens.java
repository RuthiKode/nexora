package com.nexora.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.nexora.R;
import com.nexora.domain.MatchScoreCalculator;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.StudentProfile;
import com.nexora.domain.ReadinessAnalyzer;
import com.nexora.domain.Validators;
import com.nexora.ui.StudentViewModel.DriveView;
import com.nexora.ui.UiKit.Tone;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Student profile editor and the placement readiness dashboard. */
public final class ProfileScreens {
    private ProfileScreens() {}

    // =====================================================================================
    public static class ProfileFragment extends UiKit.Screen {
        private View root;
        private TextInputLayout nameL, rollL, branchL, cgpaL, backlogsL, yearL, skillL, certL, resumeL, linkedinL, githubL, summaryL;
        private ChipGroup skillChips, roleChips, certChips;
        private final List<String> skills = new ArrayList<>(), certs = new ArrayList<>();
        private StudentProfile bound;

        @Override protected String title() { return getString(R.string.nav_profile); }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_profile, container, false);
            nameL = root.findViewById(R.id.name_layout);
            rollL = root.findViewById(R.id.roll_layout);
            branchL = root.findViewById(R.id.branch_layout);
            cgpaL = root.findViewById(R.id.cgpa_layout);
            backlogsL = root.findViewById(R.id.backlogs_layout);
            yearL = root.findViewById(R.id.year_layout);
            skillL = root.findViewById(R.id.skill_layout);
            certL = root.findViewById(R.id.cert_layout);
            resumeL = root.findViewById(R.id.resume_layout);
            linkedinL = root.findViewById(R.id.linkedin_layout);
            githubL = root.findViewById(R.id.github_layout);
            summaryL = root.findViewById(R.id.summary_layout);
            skillChips = root.findViewById(R.id.skill_chips);
            roleChips = root.findViewById(R.id.role_chips);
            certChips = root.findViewById(R.id.cert_chips);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            UiKit.StateView state = new UiKit.StateView(root.findViewById(R.id.state));
            ((AutoCompleteTextView) root.findViewById(R.id.branch)).setAdapter(
                    new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, Models.BRANCHES));
            for (Models.RoleCategory r : Models.RoleCategory.values()) UiKit.chip(roleChips, r.label, true, false).setTag(r.name());
            skillL.setEndIconOnClickListener(x -> addSkill());
            certL.setEndIconOnClickListener(x -> addCert());
            skillL.getEditText().setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) addSkill(); return true; });
            certL.getEditText().setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) addCert(); return true; });
            root.findViewById(R.id.save).setOnClickListener(x -> save());
            student().profile.observe(getViewLifecycleOwner(), r -> {
                if (r.data == null) {
                    if (r.isError()) state.error(r.message, null); else state.loading();
                    return;
                }
                state.hide();
                // Rebind only on first load or when verification state changes, so typing isn't overwritten.
                if (bound == null || bound.academicVerified != r.data.academicVerified) bind(r.data);
                bound = r.data;
                header(r.data);
            });
        }

        private void header(StudentProfile p) {
            ((TextView) root.findViewById(R.id.p_avatar)).setText(Models.initials(p.name));
            ((TextView) root.findViewById(R.id.p_name)).setText(p.name);
            ((TextView) root.findViewById(R.id.p_email)).setText(p.email);
            UiKit.badge(root.findViewById(R.id.p_status), p.academicVerified ? "Verified record" : "Verification pending",
                    p.academicVerified ? Tone.SUCCESS : Tone.WARNING);
        }

        private void bind(StudentProfile p) {
            UiKit.setText(nameL, p.name);
            UiKit.setText(rollL, p.rollNumber);
            ((AutoCompleteTextView) root.findViewById(R.id.branch)).setText(p.branch == null ? "" : p.branch, false);
            UiKit.setText(cgpaL, p.cgpa == null ? null : String.format(Locale.US, "%.2f", p.cgpa));
            UiKit.setText(backlogsL, p.activeBacklogs == null ? null : String.valueOf(p.activeBacklogs));
            UiKit.setText(yearL, p.graduationYear == null ? null : String.valueOf(p.graduationYear));
            for (TextInputLayout l : new TextInputLayout[]{rollL, branchL, cgpaL, backlogsL, yearL}) {
                l.setEnabled(!p.academicVerified);
                l.setError(null);
            }
            ((TextView) root.findViewById(R.id.academic_note)).setText(p.academicVerified
                    ? "Verified by the placement office on " + UiKit.date(p.verifiedAt) + ". These fields are locked; contact the office to request a correction."
                    : "Editable until the placement office verifies your record. Eligibility uses these values.");
            skills.clear();
            skills.addAll(p.skills);
            certs.clear();
            certs.addAll(p.certifications);
            renderChips();
            for (int i = 0; i < roleChips.getChildCount(); i++) {
                Chip c = (Chip) roleChips.getChildAt(i);
                c.setChecked(p.preferredRoles.contains((String) c.getTag()));
            }
            UiKit.setText(resumeL, p.resumeUrl);
            UiKit.setText(linkedinL, p.linkedinUrl);
            UiKit.setText(githubL, p.githubUrl);
            UiKit.setText(summaryL, p.summary);
        }

        private void renderChips() {
            UiKit.chips(skillChips, skills, s -> { skills.remove(s); renderChips(); });
            UiKit.chips(certChips, certs, s -> { certs.remove(s); renderChips(); });
        }

        private void addSkill() {
            String s = MatchScoreCalculator.normalizeSkill(UiKit.text(skillL));
            if (!s.isEmpty() && !skills.contains(s)) skills.add(s);
            UiKit.setText(skillL, null);
            renderChips();
        }

        private void addCert() {
            String s = UiKit.text(certL);
            if (!s.isEmpty() && !certs.contains(s)) certs.add(s);
            UiKit.setText(certL, null);
            renderChips();
        }

        private void save() {
            if (bound == null) return;
            if (!UiKit.text(skillL).isEmpty()) addSkill();
            if (!UiKit.text(certL).isEmpty()) addCert();
            StudentProfile p = new StudentProfile();
            p.uid = bound.uid;
            p.email = bound.email;
            p.academicVerified = bound.academicVerified;
            p.name = UiKit.text(nameL);
            boolean ok = UiKit.check(nameL, Validators.name(p.name));
            if (!bound.academicVerified) {
                // Academic fields are optional while drafting, but must be valid when filled.
                String roll = UiKit.text(rollL).toUpperCase(Locale.ROOT), branch = UiKit.text(branchL),
                        cgpa = UiKit.text(cgpaL), back = UiKit.text(backlogsL), year = UiKit.text(yearL);
                ok &= UiKit.check(rollL, roll.isEmpty() ? null : Validators.rollNumber(roll));
                ok &= UiKit.check(cgpaL, cgpa.isEmpty() ? null : Validators.cgpa(cgpa));
                ok &= UiKit.check(backlogsL, back.isEmpty() ? null : Validators.backlogs(back));
                ok &= UiKit.check(yearL, year.isEmpty() ? null : Validators.graduationYear(year, Calendar.getInstance().get(Calendar.YEAR)));
                p.rollNumber = UiKit.nullIfBlank(roll);
                p.branch = Models.BRANCHES.contains(branch) ? branch : null;
                p.cgpa = ok && !cgpa.isEmpty() ? Double.parseDouble(cgpa) : null;
                p.activeBacklogs = Validators.intOrNull(back);
                p.graduationYear = Validators.intOrNull(year);
            }
            for (TextInputLayout l : new TextInputLayout[]{resumeL, linkedinL, githubL}) ok &= UiKit.check(l, Validators.url(UiKit.text(l)));
            String summary = UiKit.text(summaryL);
            ok &= UiKit.check(summaryL, summary.length() > 400 ? "Keep the summary under 400 characters" : null);
            if (!ok) { UiKit.snack(root, "Please fix the highlighted fields."); return; }
            p.skills = new ArrayList<>(skills);
            p.certifications = new ArrayList<>(certs);
            p.preferredRoles = UiKit.checkedTags(roleChips);
            p.resumeUrl = UiKit.nullIfBlank(UiKit.text(resumeL));
            p.linkedinUrl = UiKit.nullIfBlank(UiKit.text(linkedinL));
            p.githubUrl = UiKit.nullIfBlank(UiKit.text(githubL));
            p.summary = UiKit.nullIfBlank(summary);
            Button save = root.findViewById(R.id.save);
            if (!main().isOnline()) {
                // Firestore queues the write offline and syncs it on reconnect.
                student().saveProfile(p, r -> { });
                UiKit.snack(root, "Saved on this device — it will sync when you're back online.");
                return;
            }
            save.setEnabled(false);
            student().saveProfile(p, r -> {
                save.setEnabled(true);
                UiKit.snack(root, r.isSuccess() ? "Profile saved" : r.message);
            });
        }
    }

    // =====================================================================================
    public static class ReadinessFragment extends UiKit.Screen {
        private View root;

        @Override protected String title() { return getString(R.string.nav_readiness); }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_readiness, container, false);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            root.findViewById(R.id.log_fab).setOnClickListener(x -> logDialog());
            student().snapshot.observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });
        }

        private void bind(StudentViewModel.Snapshot s) {
            ReadinessAnalyzer.Report rep = s.readiness;
            ((ChartView) root.findViewById(R.id.overall_ring)).setRing(rep.overall, "overall", null);
            ((TextView) root.findViewById(R.id.window_text)).setText(rep.activitiesInWindow
                    + (rep.activitiesInWindow == 1 ? " activity" : " activities") + " logged in the last 30 days.");
            LinearLayout bars = root.findViewById(R.id.component_bars);
            bars.removeAllViews();
            for (Component c : Component.values()) {
                int pct = rep.percents.get(c);
                UiKit.progress(bars, c.label + " · weight " + c.weight + "%", pct, pct + "%");
            }
            List<String> labels = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            for (ReadinessAnalyzer.WeeklyPoint w : rep.last6Weeks) {
                labels.add(ChartView.weekLabel(w.weekStart + 7 * ReadinessAnalyzer.DAY - 1));
                values.add(w.activities);
            }
            ((ChartView) root.findViewById(R.id.weekly_chart)).setLine(labels, values);

            LinearLayout sug = root.findViewById(R.id.suggestions);
            sug.removeAllViews();
            if (rep.suggestions.isEmpty()) {
                UiKit.criterion(sug, R.drawable.ic_check, Tone.SUCCESS, "You're on track", null, "Every component is complete. Keep practising.");
            }
            for (int i = 0; i < rep.suggestions.size(); i++) {
                UiKit.criterion(sug, R.drawable.ic_trending, i == 0 ? Tone.WARNING : Tone.PRIMARY, null, null, rep.suggestions.get(i));
            }

            LinearLayout rel = root.findViewById(R.id.relevant_drives);
            rel.removeAllViews();
            for (String id : rep.relevantDriveIds) {
                DriveView dv = s.find(id);
                if (dv == null) continue;
                View row = UiKit.inflate(rel, R.layout.item_row);
                DriveScreens.bindDriveRow(row, dv, this, false);
                List<String> focus = new ArrayList<>();
                for (String f : dv.drive.prepFocus) focus.add(UiKit.title(f));
                if (!focus.isEmpty()) ((TextView) row.findViewById(R.id.meta)).setText(
                        "Focus: " + String.join(", ", focus) + " · " + UiKit.deadline(dv.drive.deadline));
                rel.addView(row);
            }
            root.findViewById(R.id.relevant_empty).setVisibility(rel.getChildCount() == 0 ? View.VISIBLE : View.GONE);

            LinearLayout recent = root.findViewById(R.id.recent);
            recent.removeAllViews();
            for (Activity a : s.activities.subList(0, Math.min(10, s.activities.size()))) {
                View row = UiKit.inflate(recent, R.layout.item_row);
                String detail = UiKit.join(" · ", a.scorePercent == null ? null : "Score " + a.scorePercent + "%",
                        a.rating == null ? null : "Rating " + a.rating + "/5", a.durationMin + " min");
                UiKit.row(row, a.type.substring(0, 2), Tone.PRIMARY, a.title, detail, UiKit.date(a.date),
                        UiKit.title(a.type), Tone.NEUTRAL);
                recent.addView(row);
            }
            root.findViewById(R.id.recent_empty).setVisibility(s.activities.isEmpty() ? View.VISIBLE : View.GONE);
        }

        private void logDialog() {
            View body = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_activity, null);
            ChipGroup types = body.findViewById(R.id.type_group);
            for (Component c : Component.ACTIVITY_TYPES) UiKit.chip(types, c.label, true, c == Component.CODING).setTag(c.name());
            TextInputLayout titleL = body.findViewById(R.id.act_title_layout), scoreL = body.findViewById(R.id.score_layout),
                    durL = body.findViewById(R.id.duration_layout);
            View ratingRow = body.findViewById(R.id.rating_row);
            Slider rating = body.findViewById(R.id.rating);
            TextView ratingLabel = body.findViewById(R.id.rating_label);
            rating.addOnChangeListener((sl, v, u) -> ratingLabel.setText("Interviewer rating: " + (int) v + " / 5"));
            ratingLabel.setText("Interviewer rating: 3 / 5");
            Runnable sync = () -> {
                String t = UiKit.checkedTags(types).isEmpty() ? "CODING" : UiKit.checkedTags(types).get(0);
                boolean scored = t.equals("APTITUDE") || t.equals("CODING");
                scoreL.setVisibility(scored ? View.VISIBLE : View.GONE);
                ratingRow.setVisibility(t.equals("INTERVIEW") ? View.VISIBLE : View.GONE);
            };
            types.setOnCheckedStateChangeListener((g, ids) -> sync.run());
            sync.run();
            Button dateBtn = body.findViewById(R.id.act_date);
            final Date[] date = {new Date()};
            dateBtn.setText("Date: " + UiKit.date(date[0]));
            dateBtn.setOnClickListener(x -> UiKit.pickDate(getChildFragmentManager(), date[0], false, false, true, d -> {
                date[0] = d;
                dateBtn.setText("Date: " + UiKit.date(d));
            }));
            androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.log_activity)
                    .setView(body).setPositiveButton("Save", null).setNegativeButton(android.R.string.cancel, null).create();
            dlg.setOnShowListener(x -> dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Activity a = new Activity();
                a.type = UiKit.checkedTags(types).get(0);
                a.title = UiKit.text(titleL);
                boolean scored = a.type.equals("APTITUDE") || a.type.equals("CODING");
                a.scorePercent = scored ? Validators.intOrNull(UiKit.text(scoreL)) : null;
                a.rating = a.type.equals("INTERVIEW") ? (int) rating.getValue() : null;
                Integer dur = Validators.intOrNull(UiKit.text(durL));
                a.durationMin = dur == null ? 0 : dur;
                a.date = date[0];
                boolean ok = UiKit.check(titleL, Validators.required(a.title, "a short title"));
                String err = Validators.activity(a.type, a.scorePercent, a.rating, a.durationMin, a.date, System.currentTimeMillis() + 60_000);
                scoreL.setError(null);
                durL.setError(null);
                if (err != null) {
                    if (err.startsWith("Score")) scoreL.setError(err);
                    else if (err.startsWith("Duration")) durL.setError(err);
                    else UiKit.snack(body, err);
                    ok = false;
                }
                if (!ok) return;
                student().logActivity(a);
                dlg.dismiss();
                UiKit.snack(root, main().isOnline() ? "Activity logged" : "Activity saved offline — it will sync when you reconnect.");
            }));
            dlg.show();
        }
    }
}
