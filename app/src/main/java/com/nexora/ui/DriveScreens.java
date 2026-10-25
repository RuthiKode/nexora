package com.nexora.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.nexora.R;
import com.nexora.domain.EligibilityEngine;
import com.nexora.domain.MatchScoreCalculator;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Drive;
import com.nexora.ui.StudentViewModel.DriveView;
import com.nexora.ui.StudentViewModel.Filter;
import com.nexora.ui.UiKit.Tone;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Student drive discovery and the drive detail screen (explainable eligibility + match + apply). */
public final class DriveScreens {
    private DriveScreens() {}

    /** Binds a drive into item_row.xml (used by the drive list, home and readiness screens). */
    static void bindDriveRow(View v, DriveView dv, UiKit.Screen screen, boolean showBookmark) {
        Drive d = dv.drive;
        long now = System.currentTimeMillis();
        String badge;
        Tone tone;
        if (dv.application != null) {
            Models.AppStatus s = dv.application.statusEnum();
            badge = UiKit.label(s);
            tone = UiKit.tone(s);
        } else if (!d.isOpen(now)) {
            badge = "Closed";
            tone = Tone.NEUTRAL;
        } else {
            // Short labels keep row badges compact; the detail screen shows the full explanation.
            String shortLabel = dv.eligibility.status == EligibilityEngine.Status.NEEDS_VERIFICATION ? "Unverified"
                    : UiKit.label(dv.eligibility.status);
            badge = shortLabel + (dv.match != null ? " · " + dv.match.score + "%" : "");
            tone = UiKit.tone(dv.eligibility.status);
        }
        UiKit.row(v, Models.initials(d.companyName), UiKit.tone(dv.eligibility.status), d.title,
                UiKit.join(" · ", d.companyName, d.location),
                UiKit.join(" · ", UiKit.lpa(d.packageLpa), jobLabel(d.jobType), UiKit.deadline(d.deadline)), badge, tone);
        v.setOnClickListener(x -> screen.main().push(DetailFragment.of(d.id)));
        if (showBookmark) {
            UiKit.rowAction(v, dv.bookmarked ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border,
                    dv.bookmarked ? "Remove bookmark" : "Bookmark", x -> screen.student().toggleBookmark(d.id));
        }
    }

    static String jobLabel(String jobType) {
        try { return Models.JobType.valueOf(jobType).label; } catch (RuntimeException e) { return jobType; }
    }

    static String roleLabel(String role) {
        try { return Models.RoleCategory.valueOf(role).label; } catch (RuntimeException e) { return role; }
    }

    // =====================================================================================
    public static class DrivesFragment extends UiKit.ListScreen {
        private UiKit.Adapter<DriveView> adapter;
        private Chip eligibleChip, openChip, savedChip;
        private boolean syncing;

        @Override protected String title() { return getString(R.string.nav_drives); }

        @Override
        protected void setup() {
            StudentViewModel vm = student();
            enableSearch("Search title, company, location or skill");
            chipRow.setVisibility(View.VISIBLE);
            eligibleChip = quick("Eligible");
            openChip = quick("Open");
            savedChip = quick("Bookmarked");
            action.setVisibility(View.VISIBLE);
            action.setImageResource(R.drawable.ic_tune);
            action.setContentDescription("Filter and sort");
            action.setOnClickListener(x -> showFilters());
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, dv, pos) -> bindDriveRow(v, dv, this, true));
            list.setAdapter(adapter);
            vm.snapshot.observe(getViewLifecycleOwner(), s -> render());
            vm.filter.observe(getViewLifecycleOwner(), f -> {
                syncing = true;
                eligibleChip.setChecked(f.eligibleOnly);
                openChip.setChecked(f.openOnly);
                savedChip.setChecked(f.bookmarkedOnly);
                syncing = false;
                render();
            });
        }

        private Chip quick(String label) {
            Chip c = UiKit.chip(chips, label, true, false);
            c.setOnCheckedChangeListener((b, checked) -> {
                if (syncing) return;
                Filter f = student().currentFilter();
                f.eligibleOnly = eligibleChip.isChecked();
                f.openOnly = openChip.isChecked();
                f.bookmarkedOnly = savedChip.isChecked();
                student().setFilter(f);
            });
            return c;
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            StudentViewModel vm = student();
            StudentViewModel.Snapshot s = vm.snapshot.getValue();
            Filter f = vm.currentFilter();
            f.query = query();
            List<DriveView> items = s == null ? null : f.apply(s.drives, System.currentTimeMillis());
            UiKit.render(vm.drives.getValue(), items, adapter, state, refresh, R.drawable.ic_work,
                    s != null && s.drives.isEmpty() ? "No drives yet" : "No matching drives",
                    s != null && s.drives.isEmpty() ? "New placement drives will appear here when the placement office publishes them."
                            : "Try clearing some filters or searching for something else.", this::render);
            if (items != null) {
                String sort = Filter.SORT_PACKAGE.equals(f.sort) ? "package" : Filter.SORT_DEADLINE.equals(f.sort) ? "deadline" : "best match";
                int extra = f.jobTypes.size() + f.roles.size() + (f.location.isEmpty() ? 0 : 1) + (f.minPackage > 0 ? 1 : 0);
                UiKit.setOrHide(header, items.size() + (items.size() == 1 ? " drive" : " drives") + " · sorted by " + sort
                        + (extra > 0 ? " · " + extra + " more filter" + (extra == 1 ? "" : "s") : "")
                        + (s.drivesFromCache ? " · saved data" : ""));
            }
        }

        private void showFilters() {
            BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_filters, null);
            sheet.setContentView(v);
            Filter cur = student().currentFilter();
            ChipGroup sort = v.findViewById(R.id.f_sort), jobs = v.findViewById(R.id.f_jobtype), roles = v.findViewById(R.id.f_role);
            String[][] sorts = {{Filter.SORT_MATCH, "Best match"}, {Filter.SORT_DEADLINE, "Deadline"}, {Filter.SORT_PACKAGE, "Package"}};
            for (String[] s : sorts) UiKit.chip(sort, s[1], true, s[0].equals(cur.sort)).setTag(s[0]);
            for (Models.JobType j : Models.JobType.values()) UiKit.chip(jobs, j.label, true, cur.jobTypes.contains(j.name())).setTag(j.name());
            for (Models.RoleCategory r : Models.RoleCategory.values()) UiKit.chip(roles, r.label, true, cur.roles.contains(r.name())).setTag(r.name());
            TextInputLayout loc = v.findViewById(R.id.f_location_layout);
            UiKit.setText(loc, cur.location);
            Slider pkg = v.findViewById(R.id.f_package);
            TextView pkgLabel = v.findViewById(R.id.f_package_label);
            pkg.addOnChangeListener((sl, value, user) -> pkgLabel.setText(value <= 0 ? "Minimum package: any"
                    : String.format(Locale.US, "Minimum package: ₹%.0f LPA", value)));
            pkg.setValue(Math.min(50, cur.minPackage));
            pkgLabel.setText(cur.minPackage <= 0 ? "Minimum package: any" : "Minimum package: ₹" + cur.minPackage + " LPA");
            MaterialSwitch elig = v.findViewById(R.id.f_eligible), saved = v.findViewById(R.id.f_bookmarked), open = v.findViewById(R.id.f_open);
            elig.setChecked(cur.eligibleOnly);
            saved.setChecked(cur.bookmarkedOnly);
            open.setChecked(cur.openOnly);
            v.findViewById(R.id.f_reset).setOnClickListener(x -> { student().setFilter(new Filter()); sheet.dismiss(); });
            v.findViewById(R.id.f_apply).setOnClickListener(x -> {
                Filter f = new Filter();
                List<String> s = UiKit.checkedTags(sort);
                f.sort = s.isEmpty() ? Filter.SORT_MATCH : s.get(0);
                f.jobTypes = new HashSet<>(UiKit.checkedTags(jobs));
                f.roles = new HashSet<>(UiKit.checkedTags(roles));
                f.location = UiKit.text(loc);
                f.minPackage = (int) pkg.getValue();
                f.eligibleOnly = elig.isChecked();
                f.bookmarkedOnly = saved.isChecked();
                f.openOnly = open.isChecked();
                student().setFilter(f);
                sheet.dismiss();
            });
            sheet.show();
        }
    }

    // =====================================================================================
    public static class DetailFragment extends UiKit.Screen {
        private View root;
        private UiKit.StateView state;

        static DetailFragment of(String driveId) {
            DetailFragment f = new DetailFragment();
            Bundle b = new Bundle();
            b.putString("driveId", driveId);
            f.setArguments(b);
            return f;
        }

        @Override protected String title() { return "Drive details"; }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_drive_detail, container, false);
            state = new UiKit.StateView(root.findViewById(R.id.state));
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            student().snapshot.observe(getViewLifecycleOwner(), s -> {
                DriveView dv = s == null ? null : s.find(arg("driveId"));
                if (s == null) state.loading();
                else if (dv == null) state.empty(R.drawable.ic_work, "Drive unavailable",
                        "This drive is no longer visible. It may have been withdrawn by the placement office.");
                else { state.hide(); bind(dv, s.profile); }
            });
        }

        private void bind(DriveView dv, Models.StudentProfile p) {
            Drive d = dv.drive;
            ((TextView) root.findViewById(R.id.logo)).setText(Models.initials(d.companyName));
            ((TextView) root.findViewById(R.id.title)).setText(d.title);
            ((TextView) root.findViewById(R.id.company)).setText(d.companyName);
            ImageButton mark = root.findViewById(R.id.bookmark);
            mark.setImageResource(dv.bookmarked ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border);
            mark.setContentDescription(dv.bookmarked ? "Remove bookmark" : "Bookmark");
            mark.setOnClickListener(x -> student().toggleBookmark(d.id));
            ChipGroup info = root.findViewById(R.id.info_chips);
            UiKit.chips(info, java.util.Arrays.asList(UiKit.lpa(d.packageLpa), jobLabel(d.jobType),
                    roleLabel(d.roleCategory), d.location == null ? "Location TBA" : d.location), null);
            ((TextView) root.findViewById(R.id.deadline)).setText(UiKit.deadline(d.deadline));

            // Eligibility card
            EligibilityEngine.Result e = dv.eligibility;
            UiKit.badge(root.findViewById(R.id.elig_badge), UiKit.label(e.status), UiKit.tone(e.status));
            ((TextView) root.findViewById(R.id.elig_summary)).setText(e.summary);
            LinearLayout crit = root.findViewById(R.id.criteria);
            crit.removeAllViews();
            for (EligibilityEngine.CriterionResult c : e.criteria) {
                int icon = c.outcome == EligibilityEngine.Outcome.PASS ? R.drawable.ic_check
                        : c.outcome == EligibilityEngine.Outcome.FAIL ? R.drawable.ic_close : R.drawable.ic_help;
                Tone tone = c.outcome == EligibilityEngine.Outcome.PASS ? Tone.SUCCESS
                        : c.outcome == EligibilityEngine.Outcome.FAIL ? Tone.DANGER : Tone.WARNING;
                UiKit.criterion(crit, icon, tone, c.criterion.label, c.studentValue + "  /  " + c.requiredValue, c.message);
            }
            if (e.status != EligibilityEngine.Status.NOT_ELIGIBLE && !p.academicVerified) {
                UiKit.criterion(crit, R.drawable.ic_lock, Tone.WARNING, "Verification", "Pending",
                        EligibilityEngine.UNVERIFIED_MESSAGE);
            }

            // Match card
            MatchScoreCalculator.Result m = dv.match;
            View matchCard = root.findViewById(R.id.match_card);
            matchCard.setVisibility(m == null ? View.GONE : View.VISIBLE);
            if (m != null) {
                Tone band = m.band == MatchScoreCalculator.Band.STRONG ? Tone.SUCCESS
                        : m.band == MatchScoreCalculator.Band.GOOD ? Tone.PRIMARY : Tone.WARNING;
                ChartView ring = root.findViewById(R.id.match_ring);
                ring.setRing(m.score, "match", UiKit.color(requireContext(), band.fg));
                UiKit.badge(root.findViewById(R.id.match_band), UiKit.title(m.band.name()) + " match"
                        + (m.provisional ? " · provisional" : ""), band);
                ((TextView) root.findViewById(R.id.match_formula)).setText(String.format(Locale.US,
                        "0.60 × %d + 0.25 × %d + 0.15 × %d = %d", m.s, m.p, m.r, m.score));
                LinearLayout bars = root.findViewById(R.id.match_bars);
                bars.removeAllViews();
                UiKit.progress(bars, "Skills coverage (60%)", m.s, m.s + "%");
                UiKit.progress(bars, "Role preference (25%)", m.p, m.p + "%");
                UiKit.progress(bars, "Readiness for this drive (15%)", m.r, m.r + "%");
                ChipGroup matched = root.findViewById(R.id.matched_chips), missing = root.findViewById(R.id.missing_chips);
                UiKit.chips(matched, m.matchedSkills, null);
                UiKit.chips(missing, m.missingSkills, null);
                root.findViewById(R.id.matched_label).setVisibility(m.matchedSkills.isEmpty() ? View.GONE : View.VISIBLE);
                root.findViewById(R.id.missing_label).setVisibility(m.missingSkills.isEmpty() ? View.GONE : View.VISIBLE);
                LinearLayout reasons = root.findViewById(R.id.reasons);
                reasons.removeAllViews();
                for (String r : m.reasons) UiKit.criterion(reasons, R.drawable.ic_chevron_right, Tone.PRIMARY, null, null, r);
            }

            // About, company, stages
            UiKit.setOrHide(root.findViewById(R.id.description), d.description == null ? "No description provided." : d.description);
            UiKit.chips(root.findViewById(R.id.required_chips), d.requiredSkills, null);
            View companyCard = root.findViewById(R.id.company_card);
            companyCard.setVisibility(dv.company == null ? View.GONE : View.VISIBLE);
            if (dv.company != null) {
                ((TextView) root.findViewById(R.id.company_name)).setText(UiKit.join(" · ", dv.company.name, dv.company.sector));
                UiKit.setOrHide(root.findViewById(R.id.company_desc), dv.company.description);
                Button site = root.findViewById(R.id.company_site);
                site.setVisibility(dv.company.website == null ? View.GONE : View.VISIBLE);
                site.setOnClickListener(x -> UiKit.openUrl(requireContext(), dv.company.website));
            }
            StudentScreens.timeline(root.findViewById(R.id.stages), d, null);

            // Apply bar
            Button apply = root.findViewById(R.id.apply);
            TextView reason = root.findViewById(R.id.apply_reason);
            if (dv.application != null) {
                apply.setEnabled(true);
                apply.setText("Applied · track your application");
                UiKit.setOrHide(reason, null);
                apply.setOnClickListener(x -> main().push(StudentScreens.JourneyFragment.of(dv.application.id, false)));
            } else {
                apply.setEnabled(dv.decision.allowed);
                apply.setText(R.string.apply_now);
                UiKit.setOrHide(reason, dv.decision.reason);
                apply.setOnClickListener(x -> confirmApply(dv, p));
            }
        }

        private void confirmApply(DriveView dv, Models.StudentProfile p) {
            if (!requireOnline()) return;
            String msg = "Your verified profile will be shared with the placement office:\n\n"
                    + "CGPA " + String.format(Locale.US, "%.2f", p.cgpa) + " · " + p.branch + " · "
                    + p.activeBacklogs + " active backlogs · Batch " + p.graduationYear
                    + "\nMatch score " + (dv.match == null ? "—" : dv.match.score + "%")
                    + "\n\nYou can apply only once to each drive.";
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Apply to " + dv.drive.companyName + "?")
                    .setMessage(msg).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Submit application", (dlg, w) -> student().apply(dv, r -> {
                        if (!isAdded()) return;
                        if (r.isSuccess()) {
                            new MaterialAlertDialogBuilder(requireContext()).setTitle("Application submitted")
                                    .setMessage("Your application to " + dv.drive.title + " at " + dv.drive.companyName
                                            + " was received. You'll be notified as it moves through each stage.")
                                    .setPositiveButton("Track application", (d2, w2) -> main().push(
                                            StudentScreens.JourneyFragment.of(Models.applicationId(dv.drive.id, student().uid), false)))
                                    .setNegativeButton("Done", null).show();
                        } else {
                            new MaterialAlertDialogBuilder(requireContext()).setTitle("Couldn't apply")
                                    .setMessage(r.message).setPositiveButton(android.R.string.ok, null).show();
                        }
                    })).show();
        }
    }
}
