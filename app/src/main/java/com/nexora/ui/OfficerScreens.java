package com.nexora.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexora.R;
import com.nexora.data.Resource;
import com.nexora.domain.Analytics;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;
import com.nexora.domain.Models.StudentProfile;
import com.nexora.domain.Validators;
import com.nexora.ui.UiKit.Tone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Officer dashboard, verification queue, announcements and analytics. */
public final class OfficerScreens {
    private OfficerScreens() {}

    static boolean complete(StudentProfile p) {
        return p.rollNumber != null && p.branch != null && p.cgpa != null && p.activeBacklogs != null && p.graduationYear != null;
    }

    static void bindStudentRow(View v, StudentProfile p, UiKit.Screen screen) {
        String academics = complete(p) ? String.format(Locale.US, "CGPA %.2f · %d backlogs · Batch %d", p.cgpa, p.activeBacklogs, p.graduationYear)
                : "Academic record incomplete";
        UiKit.row(v, Models.initials(p.name), p.academicVerified ? Tone.SUCCESS : Tone.WARNING, p.name,
                UiKit.join(" · ", p.rollNumber, p.branch), academics,
                p.academicVerified ? "Verified" : "Pending", p.academicVerified ? Tone.SUCCESS : Tone.WARNING);
        v.setOnClickListener(x -> review(screen, p));
    }

    /** Verification review: shows the record and lets the officer verify or request a correction. */
    static void review(UiKit.Screen screen, StudentProfile p) {
        String msg = "Roll number: " + orDash(p.rollNumber) + "\nBranch: " + orDash(p.branch)
                + "\nCGPA: " + (p.cgpa == null ? "—" : String.format(Locale.US, "%.2f", p.cgpa))
                + "\nActive backlogs: " + (p.activeBacklogs == null ? "—" : p.activeBacklogs)
                + "\nGraduation year: " + (p.graduationYear == null ? "—" : p.graduationYear)
                + "\nEmail: " + orDash(p.email)
                + (p.academicVerified ? "\n\nVerified " + UiKit.date(p.verifiedAt) + ". Un-verifying unlocks these fields so the student can correct them."
                : complete(p) ? "\n\nCheck these values against official records before verifying. Verified fields are locked."
                : "\n\nThe record is incomplete — ask the student to fill in every academic field first.");
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(screen.requireContext()).setTitle(p.name).setMessage(msg)
                .setNegativeButton("Close", null);
        Resource.Callback<Void> done = r -> UiKit.snack(screen.getView(), r.isSuccess()
                ? (p.academicVerified ? "Correction requested — record unlocked" : p.name + " verified") : r.message);
        if (p.academicVerified) {
            b.setPositiveButton("Request correction", (d, w) -> { if (screen.requireOnline()) screen.officer().setVerified(p.uid, false, done); });
        } else if (complete(p)) {
            b.setPositiveButton("Verify record", (d, w) -> { if (screen.requireOnline()) screen.officer().setVerified(p.uid, true, done); });
        }
        b.show();
    }

    private static String orDash(String s) { return s == null ? "—" : s; }

    // =====================================================================================
    public static class DashboardFragment extends UiKit.Screen {
        private View root;

        @Override protected String title() { return getString(R.string.nav_dashboard); }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_dashboard, container, false);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            OfficerViewModel vm = officer();
            stat(R.id.stat_open, R.drawable.ic_work, "Open drives", R.id.nav_officer_drives);
            stat(R.id.stat_apps, R.drawable.ic_assignment, "Total applications", R.id.nav_analytics);
            stat(R.id.stat_pending, R.drawable.ic_verified, "Pending verifications", R.id.nav_verify);
            stat(R.id.stat_selected, R.drawable.ic_check, "Selections this season", R.id.nav_analytics);
            root.findViewById(R.id.qa_new_drive).setOnClickListener(x -> main().push(DriveAdminScreens.EditorFragment.of(null)));
            root.findViewById(R.id.qa_companies).setOnClickListener(x -> main().push(new DriveAdminScreens.CompaniesFragment()));
            long start = Analytics.seasonStart(System.currentTimeMillis());
            ((TextView) root.findViewById(R.id.d_season)).setText("Season since " + UiKit.date(new java.util.Date(start)));
            UiKit.StateView state = new UiKit.StateView(root.findViewById(R.id.state));
            vm.drives.observe(getViewLifecycleOwner(), r -> render(state));
            vm.applications.observe(getViewLifecycleOwner(), r -> render(state));
            vm.profiles.observe(getViewLifecycleOwner(), r -> render(state));
        }

        private void stat(int id, @DrawableRes int icon, String label, int tab) {
            View card = root.findViewById(id);
            ((ImageView) card.findViewById(R.id.stat_icon)).setImageResource(icon);
            ((TextView) card.findViewById(R.id.stat_label)).setText(label);
            card.setOnClickListener(x -> main().selectTab(tab));
        }

        private void setStat(int id, int value) {
            View card = root.findViewById(id);
            ((TextView) card.findViewById(R.id.stat_value)).setText(String.valueOf(value));
            card.setContentDescription(((TextView) card.findViewById(R.id.stat_label)).getText() + ": " + value);
        }

        private void render(UiKit.StateView state) {
            OfficerViewModel vm = officer();
            Resource<?> d = vm.drives.getValue();
            if (d != null && d.isError() && d.data == null) { state.error(d.message, null); return; }
            state.hide();
            List<Drive> drives = OfficerViewModel.data(vm.drives);
            List<Application> apps = OfficerViewModel.data(vm.applications);
            List<StudentProfile> profiles = OfficerViewModel.data(vm.profiles);
            Analytics.Dashboard dash = vm.container().analytics.dashboard(drives, apps, profiles, System.currentTimeMillis());
            setStat(R.id.stat_open, dash.openDrives);
            setStat(R.id.stat_apps, dash.totalApplications);
            setStat(R.id.stat_pending, dash.pendingVerifications);
            setStat(R.id.stat_selected, dash.selectionsThisSeason);

            LinearLayout pending = root.findViewById(R.id.pending_list);
            pending.removeAllViews();
            for (StudentProfile p : profiles) {
                if (p.academicVerified || !complete(p) || pending.getChildCount() >= 3) continue;
                View row = UiKit.inflate(pending, R.layout.item_row);
                bindStudentRow(row, p, this);
                pending.addView(row);
            }
            root.findViewById(R.id.pending_empty).setVisibility(pending.getChildCount() == 0 ? View.VISIBLE : View.GONE);

            LinearLayout deadlines = root.findViewById(R.id.deadline_list);
            deadlines.removeAllViews();
            long now = System.currentTimeMillis();
            for (Drive dr : drives) {
                if (!dr.isOpen(now) || deadlines.getChildCount() >= 4) continue;
                View row = UiKit.inflate(deadlines, R.layout.item_row);
                DriveAdminScreens.bindDriveRow(row, dr, vm.applicationsFor(dr.id).size(), this);
                deadlines.addView(row);
            }
            root.findViewById(R.id.deadline_empty).setVisibility(deadlines.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    // =====================================================================================
    public static class VerifyFragment extends UiKit.ListScreen {
        private UiKit.Adapter<StudentProfile> adapter;

        @Override protected String title() { return "Verification queue"; }

        @Override
        protected void setup() {
            enableSearch("Search name or roll number");
            tabs("Pending", "Verified", "All");
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, p, pos) -> bindStudentRow(v, p, this));
            list.setAdapter(adapter);
            officer().profiles.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Resource<List<StudentProfile>> r = officer().profiles.getValue();
            List<StudentProfile> items = null;
            if (r != null && r.data != null) {
                items = new ArrayList<>();
                String tab = selectedTab(), q = query();
                for (StudentProfile p : r.data) {
                    if ("Pending".equals(tab) && p.academicVerified) continue;
                    if ("Verified".equals(tab) && !p.academicVerified) continue;
                    if (!q.isEmpty() && !(String.valueOf(p.name).toLowerCase(Locale.ROOT).contains(q)
                            || String.valueOf(p.rollNumber).toLowerCase(Locale.ROOT).contains(q))) continue;
                    items.add(p);
                }
                // Complete records first: they can be verified right away.
                items.sort((a, b) -> Boolean.compare(!complete(a), !complete(b)));
                UiKit.setOrHide(header, items.size() + " student" + (items.size() == 1 ? "" : "s")
                        + ("Pending".equals(tab) ? " awaiting verification" : ""));
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_verified, "Nothing to review",
                    "Student academic records waiting for verification appear here.", this::render);
        }
    }

    // =====================================================================================
    public static class AnnouncementsFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Notice> adapter;

        @Override protected String title() { return "Announcements"; }

        @Override
        protected void setup() {
            tabs("Announcements", "Application updates");
            fab.setVisibility(View.VISIBLE);
            fab.setText("New announcement");
            fab.setIconResource(R.drawable.ic_add);
            fab.setOnClickListener(x -> compose());
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, n, pos) -> {
                boolean personal = Notice.STUDENT.equals(n.audience);
                UiKit.row(v, personal ? "UP" : "AN", personal ? Tone.PRIMARY : Tone.NEUTRAL, n.title, n.body,
                        UiKit.dateTime(n.createdAt), personal ? "To student" : "All students", personal ? Tone.PRIMARY : Tone.SUCCESS);
                ((TextView) v.findViewById(R.id.subtitle)).setMaxLines(6);
            });
            list.setAdapter(adapter);
            officer().notices.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Resource<List<Notice>> r = officer().notices.getValue();
            List<Notice> items = null;
            if (r != null && r.data != null) {
                items = new ArrayList<>();
                boolean personal = "Application updates".equals(selectedTab());
                for (Notice n : r.data) if (Notice.STUDENT.equals(n.audience) == personal) items.add(n);
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_campaign, "No announcements yet",
                    "Publish an announcement to notify every student.", this::render);
        }

        private void compose() {
            UiKit.form(requireContext(), "New announcement", "Visible to every student and delivered as a notification.",
                    new String[]{"Title", "Message"}, null,
                    new int[]{InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE},
                    "Publish", (vals, f) -> {
                        boolean ok = UiKit.check(f[0], Validators.required(vals[0], "a title"));
                        ok &= UiKit.check(f[1], Validators.required(vals[1], "a message"));
                        if (!ok || !requireOnline()) return false;
                        officer().announce(vals[0], vals[1], r -> UiKit.snack(getView(), r.isSuccess() ? "Announcement published" : r.message));
                        return true;
                    });
        }
    }

    // =====================================================================================
    public static class AnalyticsFragment extends UiKit.Screen {
        private View root;
        private final List<Drive> funnelDrives = new ArrayList<>();
        private int funnelIndex;
        private boolean readinessRequested;

        @Override protected String title() { return getString(R.string.nav_analytics); }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_analytics, container, false);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            OfficerViewModel vm = officer();
            AutoCompleteTextView picker = root.findViewById(R.id.funnel_drive);
            picker.setOnItemClickListener((p, v, pos, id) -> { funnelIndex = pos; renderFunnel(); });
            vm.drives.observe(getViewLifecycleOwner(), r -> render());
            vm.applications.observe(getViewLifecycleOwner(), r -> {
                render();
                if (!readinessRequested && r != null && r.isSuccess()) { readinessRequested = true; vm.loadReadiness(); }
            });
            vm.readiness.observe(getViewLifecycleOwner(), this::renderReadiness);
        }

        private void render() {
            OfficerViewModel vm = officer();
            Analytics an = vm.container().analytics;
            List<Drive> drives = new ArrayList<>();
            for (Drive d : OfficerViewModel.data(vm.drives)) if (!Models.DriveStatus.DRAFT.name().equals(d.status)) drives.add(d);
            List<Application> apps = OfficerViewModel.data(vm.applications);

            Map<String, Integer> perDrive = an.applicationsPerDrive(drives, apps);
            ((ChartView) root.findViewById(R.id.per_drive_chart)).setHorizontalBars(new ArrayList<>(perDrive.keySet()), new ArrayList<>(perDrive.values()));

            funnelDrives.clear();
            funnelDrives.addAll(drives);
            List<String> names = new ArrayList<>();
            for (Drive d : drives) names.add(d.companyName + " · " + d.title);
            AutoCompleteTextView picker = root.findViewById(R.id.funnel_drive);
            picker.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, names));
            if (funnelIndex >= names.size()) funnelIndex = 0;
            if (!names.isEmpty()) picker.setText(names.get(funnelIndex), false);
            renderFunnel();

            Map<String, Integer> branches = an.branchSelections(apps);
            ((ChartView) root.findViewById(R.id.branch_chart)).setVerticalBars(new ArrayList<>(branches.keySet()), new ArrayList<>(branches.values()));
        }

        private void renderFunnel() {
            ChartView chart = root.findViewById(R.id.funnel_chart);
            if (funnelDrives.isEmpty()) { chart.setHorizontalBars(new ArrayList<>(), new ArrayList<>()); return; }
            Map<String, Integer> f = officer().container().analytics.funnel(funnelDrives.get(funnelIndex), OfficerViewModel.data(officer().applications));
            chart.setHorizontalBars(new ArrayList<>(f.keySet()), new ArrayList<>(f.values()));
        }

        private void renderReadiness(Resource<Map<String, Integer>> r) {
            LinearLayout rows = root.findViewById(R.id.readiness_rows);
            TextView caption = root.findViewById(R.id.readiness_caption);
            rows.removeAllViews();
            if (r == null || r.isLoading()) { caption.setText("Calculating from applicants' logged activity…"); return; }
            if (r.isError()) { caption.setText("Couldn't load readiness: " + r.message); return; }
            int[] cmp = officer().container().analytics.readinessComparison(OfficerViewModel.data(officer().applications), r.data);
            UiKit.progress(rows, "Average readiness — all applicants", Math.max(0, cmp[0]), cmp[0] < 0 ? "—" : cmp[0] + "%");
            UiKit.progress(rows, "Average readiness — selected students", Math.max(0, cmp[1]), cmp[1] < 0 ? "—" : cmp[1] + "%");
            caption.setText("Based on " + r.data.size() + " applicants' self-logged preparation in the last 30 days. Readiness is a guide, not a predictor.");
        }
    }
}
