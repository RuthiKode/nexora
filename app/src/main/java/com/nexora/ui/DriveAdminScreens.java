package com.nexora.ui;

import android.os.Bundle;
import android.text.InputType;
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
import com.google.android.material.textfield.TextInputLayout;
import com.nexora.R;
import com.nexora.data.Resource;
import com.nexora.domain.MatchScoreCalculator;
import com.nexora.domain.Models;
import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Company;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.DriveStatus;
import com.nexora.domain.Models.Stage;
import com.nexora.domain.Validators;
import com.nexora.ui.UiKit.Tone;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Officer drive management: drive list, applicants, drive editor and companies. */
public final class DriveAdminScreens {
    private DriveAdminScreens() {}

    static void bindDriveRow(View v, Drive d, int applicants, UiKit.Screen screen) {
        UiKit.row(v, Models.initials(d.companyName), UiKit.driveTone(d.status), d.title,
                UiKit.join(" · ", d.companyName, UiKit.lpa(d.packageLpa)),
                UiKit.join(" · ", applicants + (applicants == 1 ? " applicant" : " applicants"), UiKit.deadline(d.deadline)),
                UiKit.title(d.status), UiKit.driveTone(d.status));
        v.setOnClickListener(x -> screen.main().push(ApplicantsFragment.of(d.id)));
    }

    // =====================================================================================
    public static class DrivesFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Drive> adapter;

        @Override protected String title() { return "Placement drives"; }

        @Override
        protected void setup() {
            enableSearch("Search title or company");
            tabs("All", "Open", "Draft", "Closed");
            action.setVisibility(View.VISIBLE);
            action.setImageResource(R.drawable.ic_business);
            action.setContentDescription(getString(R.string.companies));
            action.setOnClickListener(x -> main().push(new CompaniesFragment()));
            fab.setVisibility(View.VISIBLE);
            fab.setText(R.string.new_drive);
            fab.setIconResource(R.drawable.ic_add);
            fab.setOnClickListener(x -> main().push(EditorFragment.of(null)));
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, d, pos) -> bindDriveRow(v, d, officer().applicationsFor(d.id).size(), this));
            list.setAdapter(adapter);
            officer().drives.observe(getViewLifecycleOwner(), r -> render());
            officer().applications.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Resource<List<Drive>> r = officer().drives.getValue();
            List<Drive> items = null;
            if (r != null && r.data != null) {
                items = new ArrayList<>();
                String tab = selectedTab(), q = query();
                for (Drive d : r.data) {
                    if (!"All".equals(tab) && !tab.toUpperCase(Locale.ROOT).equals(d.status)) continue;
                    if (!q.isEmpty() && !(String.valueOf(d.title).toLowerCase(Locale.ROOT).contains(q)
                            || String.valueOf(d.companyName).toLowerCase(Locale.ROOT).contains(q))) continue;
                    items.add(d);
                }
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_work, "No drives here",
                    "Create a drive, set its criteria and stages, then publish it to students.", this::render);
        }
    }

    // =====================================================================================
    public static class ApplicantsFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Application> adapter;
        private int stageFilter = -1;

        static ApplicantsFragment of(String driveId) {
            ApplicantsFragment f = new ApplicantsFragment();
            Bundle b = new Bundle();
            b.putString("driveId", driveId);
            f.setArguments(b);
            return f;
        }

        @Override protected String title() {
            Drive d = officer().drive(arg("driveId"));
            return d == null ? "Applicants" : d.companyName + " · " + d.title;
        }

        @Override
        protected void setup() {
            enableSearch("Search name or roll number");
            tabs("All", "Active", "Selected", "Rejected");
            action.setVisibility(View.VISIBLE);
            action.setImageResource(R.drawable.ic_tune);
            action.setContentDescription("Filter by stage");
            action.setOnClickListener(x -> pickStage());
            fab.setVisibility(View.VISIBLE);
            fab.setText("Edit drive");
            fab.setIconResource(R.drawable.ic_edit);
            fab.setOnClickListener(x -> main().push(EditorFragment.of(arg("driveId"))));
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, a, pos) -> {
                Drive d = officer().drive(a.driveId);
                AppStatus st = a.statusEnum();
                String stage = d != null && a.currentStage < d.stages.size() ? d.stages.get(a.currentStage).name : null;
                UiKit.row(v, Models.initials(a.studentName), UiKit.tone(st), a.studentName,
                        UiKit.join(" · ", a.studentRoll, a.snapBranch, a.snapCgpa == null ? null : String.format(Locale.US, "CGPA %.2f", a.snapCgpa)),
                        UiKit.join(" · ", stage == null ? null : "Stage: " + stage, "Match " + a.matchScore + "%"),
                        UiKit.label(st), UiKit.tone(st));
                v.setOnClickListener(x -> main().push(StudentScreens.JourneyFragment.of(a.id, true)));
            });
            list.setAdapter(adapter);
            officer().applications.observe(getViewLifecycleOwner(), r -> render());
            officer().drives.observe(getViewLifecycleOwner(), r -> render());
        }

        private void pickStage() {
            Drive d = officer().drive(arg("driveId"));
            if (d == null) return;
            String[] names = new String[d.stages.size() + 1];
            names[0] = "All stages";
            for (int i = 0; i < d.stages.size(); i++) names[i + 1] = d.stages.get(i).name;
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Filter by stage")
                    .setSingleChoiceItems(names, stageFilter + 1, (dlg, w) -> { stageFilter = w - 1; dlg.dismiss(); render(); }).show();
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Drive d = officer().drive(arg("driveId"));
            Resource<List<Application>> r = officer().applications.getValue();
            List<Application> items = null;
            if (d != null && r != null && r.data != null) {
                requireActivity().setTitle(title());
                List<Application> all = officer().applicationsFor(d.id);
                officer().syncApplicantCount(d, all.size());
                items = new ArrayList<>();
                String tab = selectedTab(), q = query();
                for (Application a : all) {
                    AppStatus st = a.statusEnum();
                    if ("Active".equals(tab) && st.isFinal()) continue;
                    if ("Selected".equals(tab) && st != AppStatus.SELECTED) continue;
                    if ("Rejected".equals(tab) && st != AppStatus.REJECTED) continue;
                    if (stageFilter >= 0 && a.currentStage != stageFilter) continue;
                    if (!q.isEmpty() && !(String.valueOf(a.studentName).toLowerCase(Locale.ROOT).contains(q)
                            || String.valueOf(a.studentRoll).toLowerCase(Locale.ROOT).contains(q))) continue;
                    items.add(a);
                }
                items.sort((x, y) -> y.matchScore - x.matchScore);
                UiKit.setOrHide(header, UiKit.join(" · ", UiKit.title(d.status), all.size() + " applicants",
                        stageFilter >= 0 ? "Stage: " + d.stages.get(stageFilter).name : null, UiKit.deadline(d.deadline)));
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_person, "No applicants yet",
                    d != null && DriveStatus.DRAFT.name().equals(d.status)
                            ? "This drive is a draft. Publish it so eligible students can apply."
                            : "Applications from eligible students will appear here.", this::render);
        }
    }

    // =====================================================================================
    public static class EditorFragment extends UiKit.Screen {
        private View root;
        private Drive drive;
        private boolean bound, locked;
        private final List<String> skills = new ArrayList<>();
        private final List<Company> companies = new ArrayList<>();
        private TextInputLayout titleL, companyL, roleL, jobL, locationL, packageL, descL, cgpaL, backlogsL, skillL;
        private ChipGroup branches, years, focus, skillChips;
        private LinearLayout stagesBox;
        private Button deadlineBtn;

        static EditorFragment of(String driveId) {
            EditorFragment f = new EditorFragment();
            Bundle b = new Bundle();
            b.putString("driveId", driveId);
            f.setArguments(b);
            return f;
        }

        @Override protected String title() { return arg("driveId") == null ? "New drive" : "Edit drive"; }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_drive_editor, container, false);
            titleL = root.findViewById(R.id.e_title_layout);
            companyL = root.findViewById(R.id.e_company_layout);
            roleL = root.findViewById(R.id.e_role_layout);
            jobL = root.findViewById(R.id.e_jobtype_layout);
            locationL = root.findViewById(R.id.e_location_layout);
            packageL = root.findViewById(R.id.e_package_layout);
            descL = root.findViewById(R.id.e_desc_layout);
            cgpaL = root.findViewById(R.id.e_cgpa_layout);
            backlogsL = root.findViewById(R.id.e_backlogs_layout);
            skillL = root.findViewById(R.id.e_skill_layout);
            branches = root.findViewById(R.id.e_branches);
            years = root.findViewById(R.id.e_years);
            focus = root.findViewById(R.id.e_focus);
            skillChips = root.findViewById(R.id.e_skills);
            stagesBox = root.findViewById(R.id.e_stages);
            deadlineBtn = root.findViewById(R.id.e_deadline);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            int year = Calendar.getInstance().get(Calendar.YEAR);
            for (String b : Models.BRANCHES) UiKit.chip(branches, b, true, false).setTag(b);
            for (int y = year; y <= year + 5; y++) UiKit.chip(years, String.valueOf(y), true, false).setTag(String.valueOf(y));
            // Clear the criteria error as soon as the selection changes; save() re-validates.
            ChipGroup.OnCheckedStateChangeListener clearCriteria =
                    (g, ids) -> root.findViewById(R.id.e_criteria_error).setVisibility(View.GONE);
            branches.setOnCheckedStateChangeListener(clearCriteria);
            years.setOnCheckedStateChangeListener(clearCriteria);
            for (Models.Component c : Models.Component.ACTIVITY_TYPES) UiKit.chip(focus, c.label, true, false).setTag(c.name());
            List<String> roleLabels = new ArrayList<>(), jobLabels = new ArrayList<>();
            for (Models.RoleCategory r : Models.RoleCategory.values()) roleLabels.add(r.label);
            for (Models.JobType j : Models.JobType.values()) jobLabels.add(j.label);
            dropdown(roleL, roleLabels);
            dropdown(jobL, jobLabels);
            skillL.setEndIconOnClickListener(x -> addSkill());
            skillL.getEditText().setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) addSkill(); return true; });
            // Same rules as save(); errors clear as soon as a field becomes valid.
            UiKit.revalidate(companyL, v -> companyByName(v) == null ? "Choose a company" : null);
            UiKit.revalidate(titleL, v -> Validators.required(v, "a title"));
            UiKit.revalidate(packageL, Validators::packageLpa);
            UiKit.revalidate(cgpaL, Validators::minCgpa);
            UiKit.revalidate(backlogsL, Validators::backlogs);
            UiKit.revalidate(roleL, v -> v.isEmpty() ? "Choose a role category" : null);
            UiKit.revalidate(jobL, v -> v.isEmpty() ? "Choose a job type" : null);
            deadlineBtn.setOnClickListener(x -> UiKit.pickDate(getChildFragmentManager(), drive.deadline, true, true, false, d -> {
                drive.deadline = d;
                renderDeadline();
            }));
            root.findViewById(R.id.e_add_stage).setOnClickListener(x -> {
                collectStages();
                drive.stages.add(drive.stages.size() - 1, new Stage("Round " + drive.stages.size(), "ROUND"));
                renderStages();
            });
            root.findViewById(R.id.e_save).setOnClickListener(x -> save(null));
            OfficerViewModel vm = officer();
            vm.companies.observe(getViewLifecycleOwner(), r -> {
                companies.clear();
                companies.addAll(OfficerViewModel.data(vm.companies));
                List<String> names = new ArrayList<>();
                for (Company c : companies) names.add(c.name);
                dropdown(companyL, names);
                companyL.setHelperText(companies.isEmpty() ? "Add a company first (Companies screen)" : null);
                tryBind();
            });
            vm.drives.observe(getViewLifecycleOwner(), r -> tryBind());
        }

        private Company companyByName(String name) {
            for (Company c : companies) if (c.name.equals(name)) return c;
            return null;
        }

        private void dropdown(TextInputLayout l, List<String> items) {
            ((AutoCompleteTextView) l.getEditText()).setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items));
        }

        private void setDropdown(TextInputLayout l, String value) {
            ((AutoCompleteTextView) l.getEditText()).setText(value == null ? "" : value, false);
        }

        private void tryBind() {
            if (bound) return;
            String id = arg("driveId");
            if (id == null) {
                drive = new Drive();
                drive.stages = Drive.defaultStages();
                drive.maxActiveBacklogs = 0;
            } else {
                Drive d = officer().drive(id);
                if (d == null) return;
                drive = d;
                locked = !officer().applicationsFor(id).isEmpty();
            }
            bound = true;
            UiKit.setText(titleL, drive.title);
            setDropdown(companyL, drive.companyName);
            setDropdown(roleL, DriveScreens.roleLabel(drive.roleCategory));
            setDropdown(jobL, DriveScreens.jobLabel(drive.jobType));
            UiKit.setText(locationL, drive.location);
            UiKit.setText(packageL, drive.id == null ? null : String.valueOf(drive.packageLpa));
            UiKit.setText(descL, drive.description);
            UiKit.setText(cgpaL, drive.id == null ? null : String.format(Locale.US, "%.2f", drive.minCgpa));
            UiKit.setText(backlogsL, String.valueOf(drive.maxActiveBacklogs));
            check(branches, drive.eligibleBranches);
            List<String> ys = new ArrayList<>();
            for (Integer y : drive.graduationYears) ys.add(String.valueOf(y));
            check(years, ys);
            check(focus, drive.prepFocus);
            skills.clear();
            skills.addAll(drive.requiredSkills);
            renderSkills();
            renderDeadline();
            renderStages();
            renderStatus();
        }

        private static void check(ChipGroup g, List<String> tags) {
            for (int i = 0; i < g.getChildCount(); i++) {
                Chip c = (Chip) g.getChildAt(i);
                c.setChecked(tags.contains((String) c.getTag()));
            }
        }

        private void renderStatus() {
            UiKit.badge(root.findViewById(R.id.e_status), drive.id == null ? "New · draft" : UiKit.title(drive.status), UiKit.driveTone(drive.status));
            Button save = root.findViewById(R.id.e_save), primary = root.findViewById(R.id.e_publish);
            boolean draft = DriveStatus.DRAFT.name().equals(drive.status);
            save.setText(draft ? R.string.save_draft : R.string.save_draft);
            if (!draft) save.setText("Save changes");
            if (draft) {
                primary.setText(R.string.publish);
                primary.setOnClickListener(x -> UiKit.confirm(requireContext(), "Publish drive?",
                        "Eligible students will see this drive and can apply until the deadline.", "Publish", () -> save(DriveStatus.OPEN)));
            } else if (DriveStatus.OPEN.name().equals(drive.status)) {
                primary.setText("Close drive");
                primary.setOnClickListener(x -> UiKit.confirm(requireContext(), "Close applications?",
                        "Students will no longer be able to apply. Existing applicants stay in the pipeline.", "Close drive", () -> setStatus(DriveStatus.CLOSED)));
            } else {
                primary.setText("Reopen");
                primary.setOnClickListener(x -> save(DriveStatus.OPEN));
            }
        }

        private void renderDeadline() {
            deadlineBtn.setText(drive.deadline == null ? "Choose deadline" : "Deadline: " + UiKit.dateTime(drive.deadline));
            if (drive.deadline != null) root.findViewById(R.id.e_deadline_error).setVisibility(View.GONE);
        }

        private void renderSkills() { UiKit.chips(skillChips, skills, s -> { skills.remove(s); renderSkills(); }); }

        private void addSkill() {
            for (String s : MatchScoreCalculator.parseSkills(UiKit.text(skillL))) if (!skills.contains(s)) skills.add(s);
            UiKit.setText(skillL, null);
            renderSkills();
        }

        /** Builds stage editor rows; the first and last stages are fixed. Structure is locked once applicants exist. */
        private void renderStages() {
            stagesBox.removeAllViews();
            int last = drive.stages.size() - 1;
            for (int i = 0; i <= last; i++) {
                Stage s = drive.stages.get(i);
                final int idx = i;
                View v = UiKit.inflate(stagesBox, R.layout.item_stage_edit);
                ((TextView) v.findViewById(R.id.s_index)).setText(String.valueOf(i + 1));
                TextInputLayout name = v.findViewById(R.id.s_name_layout);
                UiKit.setText(name, s.name);
                boolean fixed = i == 0 || i == last;
                name.setEnabled(!fixed && !locked);
                v.findViewById(R.id.s_up).setVisibility(fixed || locked || i == 1 ? View.GONE : View.VISIBLE);
                v.findViewById(R.id.s_down).setVisibility(fixed || locked || i == last - 1 ? View.GONE : View.VISIBLE);
                v.findViewById(R.id.s_remove).setVisibility(fixed || locked ? View.GONE : View.VISIBLE);
                v.findViewById(R.id.s_schedule_row).setVisibility(i == 0 ? View.GONE : View.VISIBLE);
                v.findViewById(R.id.s_up).setOnClickListener(x -> move(idx, -1));
                v.findViewById(R.id.s_down).setOnClickListener(x -> move(idx, 1));
                v.findViewById(R.id.s_remove).setOnClickListener(x -> { collectStages(); drive.stages.remove(idx); renderStages(); });
                Button date = v.findViewById(R.id.s_date);
                date.setText(s.scheduledAt == null ? "Schedule" : UiKit.dateTime(s.scheduledAt));
                date.setOnClickListener(x -> UiKit.pickDate(getChildFragmentManager(), s.scheduledAt, true, true, false, d -> {
                    s.scheduledAt = d;
                    date.setText(UiKit.dateTime(d));
                }));
                UiKit.setText(v.findViewById(R.id.s_venue_layout), s.venueOrLink);
                stagesBox.addView(v);
            }
            root.findViewById(R.id.e_add_stage).setVisibility(locked ? View.GONE : View.VISIBLE);
        }

        private void move(int idx, int delta) {
            collectStages();
            Stage s = drive.stages.remove(idx);
            drive.stages.add(idx + delta, s);
            renderStages();
        }

        private void collectStages() {
            for (int i = 0; i < stagesBox.getChildCount() && i < drive.stages.size(); i++) {
                View v = stagesBox.getChildAt(i);
                drive.stages.get(i).name = UiKit.text(v.findViewById(R.id.s_name_layout));
                drive.stages.get(i).venueOrLink = UiKit.nullIfBlank(UiKit.text(v.findViewById(R.id.s_venue_layout)));
            }
        }

        /** Validates and saves; when newStatus is non-null the drive is saved with that status. */
        private void save(DriveStatus newStatus) {
            if (drive == null || !requireOnline()) return;
            collectStages();
            String companyName = UiKit.text(companyL);
            Company company = companyByName(companyName);
            boolean ok = UiKit.check(companyL, company == null ? "Choose a company" : null);
            ok &= UiKit.check(titleL, Validators.required(UiKit.text(titleL), "a title"));
            ok &= UiKit.check(packageL, Validators.packageLpa(UiKit.text(packageL)));
            ok &= UiKit.check(cgpaL, Validators.minCgpa(UiKit.text(cgpaL)));
            ok &= UiKit.check(backlogsL, Validators.backlogs(UiKit.text(backlogsL)));
            ok &= UiKit.check(roleL, UiKit.text(roleL).isEmpty() ? "Choose a role category" : null);
            ok &= UiKit.check(jobL, UiKit.text(jobL).isEmpty() ? "Choose a job type" : null);
            List<Integer> ys = new ArrayList<>();
            for (String y : UiKit.checkedTags(years)) ys.add(Integer.parseInt(y));
            String critErr = Validators.branches(UiKit.checkedTags(branches));
            if (critErr == null) critErr = Validators.graduationYears(ys);
            UiKit.setOrHide(root.findViewById(R.id.e_criteria_error), critErr);
            // A draft may be saved without a future deadline; publishing or reopening requires one.
            boolean needsDeadline = newStatus == DriveStatus.OPEN || DriveStatus.OPEN.name().equals(drive.status) || drive.deadline != null;
            String dlErr = needsDeadline ? Validators.deadline(drive.deadline, System.currentTimeMillis()) : null;
            if (DriveStatus.CLOSED.name().equals(drive.status) && newStatus == null) dlErr = null;
            UiKit.setOrHide(root.findViewById(R.id.e_deadline_error), dlErr);
            String stageErr = Validators.stages(drive.stages);
            if (stageErr == null && !Models.LAST_STAGE.equals(drive.stages.get(drive.stages.size() - 1).name)) stageErr = "The last stage must be \"Final Outcome\"";
            if (!ok || critErr != null || dlErr != null || stageErr != null) {
                UiKit.snack(root, stageErr != null ? stageErr : "Please fix the highlighted fields.");
                return;
            }
            drive.companyId = company.id;
            drive.companyName = company.name;
            drive.title = UiKit.text(titleL);
            for (Models.RoleCategory r : Models.RoleCategory.values()) if (r.label.equals(UiKit.text(roleL))) drive.roleCategory = r.name();
            for (Models.JobType j : Models.JobType.values()) if (j.label.equals(UiKit.text(jobL))) drive.jobType = j.name();
            drive.location = UiKit.nullIfBlank(UiKit.text(locationL));
            drive.packageLpa = Double.parseDouble(UiKit.text(packageL));
            drive.description = UiKit.nullIfBlank(UiKit.text(descL));
            drive.minCgpa = Double.parseDouble(UiKit.text(cgpaL));
            drive.maxActiveBacklogs = Integer.parseInt(UiKit.text(backlogsL));
            drive.eligibleBranches = UiKit.checkedTags(branches);
            drive.graduationYears = ys;
            drive.prepFocus = UiKit.checkedTags(focus);
            drive.requiredSkills = new ArrayList<>(skills);
            // Publishing (draft → open) or reopening (closed → open) announces the drive to every student.
            boolean goesLive = newStatus == DriveStatus.OPEN && !DriveStatus.OPEN.name().equals(drive.status);
            if (newStatus != null) drive.status = newStatus.name();
            officer().saveDrive(drive, r -> {
                if (r.isSuccess() && goesLive) officer().announceDrive(drive, r.data);
                if (!isAdded()) return;
                if (r.isSuccess()) {
                    drive.id = r.data;
                    UiKit.snack(getView(), goesLive ? "Drive published — students notified" : "Drive saved");
                    renderStatus();
                } else {
                    UiKit.snack(getView(), r.message);
                }
            });
        }

        private void setStatus(DriveStatus s) {
            if (!requireOnline()) return;
            officer().setDriveStatus(drive.id, s, r -> {
                if (!isAdded()) return;
                if (r.isSuccess()) { drive.status = s.name(); renderStatus(); }
                UiKit.snack(getView(), r.isSuccess() ? "Drive " + s.name().toLowerCase(Locale.ROOT) : r.message);
            });
        }
    }

    // =====================================================================================
    public static class CompaniesFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Company> adapter;

        @Override protected String title() { return getString(R.string.companies); }

        @Override
        protected void setup() {
            enableSearch("Search companies");
            fab.setVisibility(View.VISIBLE);
            fab.setText("Add company");
            fab.setIconResource(R.drawable.ic_add);
            fab.setOnClickListener(x -> edit(new Company()));
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, c, pos) -> {
                UiKit.row(v, c.logoInitials == null ? Models.initials(c.name) : c.logoInitials, Tone.PRIMARY, c.name,
                        c.sector, c.website, null, Tone.NEUTRAL);
                UiKit.rowAction(v, R.drawable.ic_edit, "Edit " + c.name, x -> edit(c));
                v.setOnClickListener(x -> edit(c));
            });
            list.setAdapter(adapter);
            officer().companies.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Resource<List<Company>> r = officer().companies.getValue();
            List<Company> items = null;
            if (r != null && r.data != null) {
                items = new ArrayList<>();
                String q = query();
                for (Company c : r.data) if (q.isEmpty() || String.valueOf(c.name).toLowerCase(Locale.ROOT).contains(q)) items.add(c);
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_business, "No companies yet",
                    "Add the recruiters visiting campus, then create drives for them.", this::render);
        }

        private void edit(Company c) {
            int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
            androidx.appcompat.app.AlertDialog d = UiKit.form(requireContext(), c.id == null ? "Add company" : "Edit company", null,
                    new String[]{"Company name", "Sector (e.g. IT services)", "Website", "Short description"},
                    new String[]{c.name, c.sector, c.website, c.description},
                    new int[]{text, text, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE},
                    "Save", (vals, f) -> {
                        boolean ok = UiKit.check(f[0], Validators.required(vals[0], "a name"));
                        ok &= UiKit.check(f[2], Validators.url(vals[2]));
                        if (!ok || !requireOnline()) return false;
                        c.name = vals[0];
                        c.sector = UiKit.nullIfBlank(vals[1]);
                        c.website = UiKit.nullIfBlank(vals[2]);
                        c.description = UiKit.nullIfBlank(vals[3]);
                        c.logoInitials = Models.initials(vals[0]);
                        officer().saveCompany(c, r -> UiKit.snack(getView(), r.isSuccess() ? "Company saved" : r.message));
                        return true;
                    });
            UiKit.revalidate(d.findViewById(R.id.f1), v -> Validators.required(v, "a name"));
            UiKit.revalidate(d.findViewById(R.id.f3), Validators::url);
        }
    }
}
