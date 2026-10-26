package com.nexora.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.nexora.R;
import com.nexora.data.Resource;
import com.nexora.domain.CareerActionEngine;
import com.nexora.domain.Models;
import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;
import com.nexora.domain.Models.StageRecord;
import com.nexora.domain.Models.StudentProfile;
import com.nexora.domain.ReadinessAnalyzer;
import com.nexora.ui.StudentViewModel.DriveView;
import com.nexora.ui.UiKit.Tone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Student home, applications list, recruitment journey (also the officer applicant view) and notifications. */
public final class StudentScreens {
    private StudentScreens() {}

    /**
     * Renders drive stages as a vertical timeline into item_timeline rows. With an application the
     * rows show done ✓ / current ● / upcoming ○ / failed ✗; without one it is a plain preview.
     */
    static void timeline(LinearLayout container, Drive d, Application a) {
        container.removeAllViews();
        int last = d.stages.size() - 1;
        for (int i = 0; i <= last; i++) {
            Models.Stage st = d.stages.get(i);
            View v = UiKit.inflate(container, R.layout.item_timeline);
            ImageView dot = v.findViewById(R.id.tl_dot);
            View line = v.findViewById(R.id.tl_line);
            String badge = null, state;
            Tone tone;
            int icon;
            StageRecord rec = null;
            if (a != null) for (StageRecord r : a.stageHistory) if (r.stageIndex == i) rec = r;
            AppStatus status = a == null ? null : a.statusEnum();
            if (a == null) {
                tone = Tone.NEUTRAL; icon = R.drawable.ic_chevron_right; state = "Stage " + (i + 1);
            } else if (status == AppStatus.REJECTED && i == a.currentStage) {
                tone = Tone.DANGER; icon = R.drawable.ic_close; badge = "Not cleared"; state = "failed";
            } else if (i < a.currentStage || (status == AppStatus.SELECTED && i == last)) {
                tone = Tone.SUCCESS; icon = R.drawable.ic_check;
                badge = i == last ? "Selected" : i == 0 ? "Received" : "Cleared"; state = "done";
            } else if (i == a.currentStage) {
                tone = Tone.PRIMARY; icon = R.drawable.ic_schedule; badge = "Current"; state = "current";
            } else {
                tone = Tone.NEUTRAL; icon = R.drawable.ic_chevron_right;
                state = status == AppStatus.REJECTED ? "not reached" : "upcoming";
            }
            dot.setImageResource(icon);
            UiKit.tint(dot, tone);
            line.setBackgroundColor(UiKit.color(container.getContext(), i < (a == null ? -1 : a.currentStage) ? R.color.nx_success : R.color.nx_outline_variant));
            line.setVisibility(i == last ? View.INVISIBLE : View.VISIBLE);
            ((TextView) v.findViewById(R.id.tl_title)).setText(st.name);
            UiKit.badge(v.findViewById(R.id.tl_badge), badge, tone);
            String when = st.scheduledAt == null ? null : UiKit.dateTime(st.scheduledAt);
            if (i == 0 && a != null) when = "Applied " + UiKit.dateTime(a.appliedAt);
            UiKit.setOrHide(v.findViewById(R.id.tl_detail), UiKit.join(" · ", when, st.venueOrLink));
            UiKit.setOrHide(v.findViewById(R.id.tl_note), rec == null || rec.note == null ? null : "“" + rec.note + "”");
            v.setContentDescription(UiKit.join(". ", st.name, state, when, st.venueOrLink, rec == null ? null : rec.note));
            container.addView(v);
        }
    }

    // =====================================================================================
    public static class HomeFragment extends UiKit.Screen {
        private View root;
        private UiKit.Adapter<DriveView> eligibleAdapter;

        @Override protected String title() { return getString(R.string.app_name); }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_home, container, false);
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            StudentViewModel vm = student();
            UiKit.StateView state = new UiKit.StateView(root.findViewById(R.id.state));
            RecyclerView eligible = root.findViewById(R.id.eligible_list);
            eligible.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            eligibleAdapter = new UiKit.Adapter<>(R.layout.item_row, (v, dv, pos) -> {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                lp.width = UiKit.dp(requireContext(), 290);
                lp.setMarginEnd(UiKit.dp(requireContext(), 10));
                v.setLayoutParams(lp);
                DriveScreens.bindDriveRow(v, dv, this, false);
            });
            eligible.setAdapter(eligibleAdapter);
            root.findViewById(R.id.readiness_card).setOnClickListener(x -> main().selectTab(R.id.nav_readiness));
            root.findViewById(R.id.see_all).setOnClickListener(x -> {
                StudentViewModel.Filter f = new StudentViewModel.Filter();
                f.eligibleOnly = true;
                f.openOnly = true;
                vm.setFilter(f);
                main().selectTab(R.id.nav_drives);
            });
            root.findViewById(R.id.announcement_card).setOnClickListener(x -> main().push(new NotificationsFragment()));
            vm.profile.observe(getViewLifecycleOwner(), r -> {
                if (r.isError() && r.data == null) state.error(r.message, null);
            });
            vm.snapshot.observe(getViewLifecycleOwner(), s -> {
                if (s == null) { state.loading(); return; }
                state.hide();
                bind(s);
            });
            if (vm.snapshot.getValue() == null) state.loading();
            vm.notices.observe(getViewLifecycleOwner(), this::bindNotice);
        }

        private void bind(StudentViewModel.Snapshot s) {
            StudentProfile p = s.profile;
            String first = p.name == null ? "there" : p.name.trim().split("\\s+")[0];
            ((TextView) root.findViewById(R.id.greeting)).setText("Hi, " + first);
            ((TextView) root.findViewById(R.id.greeting_sub)).setText(p.branch == null
                    ? "Complete your profile to unlock eligibility checks."
                    : UiKit.join(" · ", p.branch, p.graduationYear == null ? null : "Batch of " + p.graduationYear,
                    p.academicVerified ? "Verified record" : null));

            bindActions(s);
            ReadinessAnalyzer.Report rep = s.readiness;
            ((ChartView) root.findViewById(R.id.readiness_ring)).setRing(rep.overall, "ready", null);
            ((TextView) root.findViewById(R.id.readiness_hint)).setText(rep.suggestions.isEmpty()
                    ? "Great work — keep logging practice to stay ready." : rep.suggestions.get(0));

            long now = System.currentTimeMillis();
            List<DriveView> eligible = new ArrayList<>(), closing = new ArrayList<>();
            for (DriveView v : s.drives) {
                // Recommendations only list drives the student can still apply to.
                if (v.match == null || !v.drive.isOpen(now) || v.application != null) continue;
                eligible.add(v);
                if (v.drive.deadline.getTime() - now <= 7 * ReadinessAnalyzer.DAY) closing.add(v);
            }
            eligible.sort((a, b) -> b.match.score - a.match.score);
            closing.sort((a, b) -> a.drive.deadline.compareTo(b.drive.deadline));
            eligibleAdapter.submit(eligible.subList(0, Math.min(8, eligible.size())));
            TextView empty = root.findViewById(R.id.eligible_empty);
            UiKit.setOrHide(empty, eligible.isEmpty() ? (p.academicVerified
                    ? "No open drives match your eligibility right now. Check back soon."
                    : "Drives you qualify for will appear here — provisionally until your record is verified.") : null);

            LinearLayout closingList = root.findViewById(R.id.closing_list);
            closingList.removeAllViews();
            for (DriveView v : closing.subList(0, Math.min(3, closing.size()))) {
                View row = UiKit.inflate(closingList, R.layout.item_row);
                DriveScreens.bindDriveRow(row, v, this, false);
                closingList.addView(row);
            }
            root.findViewById(R.id.closing_empty).setVisibility(closing.isEmpty() ? View.VISIBLE : View.GONE);
        }

        /** Career Action Plan: one tappable row per action, each opening the screen where it can be done. */
        private void bindActions(StudentViewModel.Snapshot s) {
            LinearLayout list = root.findViewById(R.id.actions_list);
            list.removeAllViews();
            UiKit.setOrHide(root.findViewById(R.id.actions_note), s.drivesFromCache
                    ? "Based on saved data — may be out of date until you're back online." : null);
            if (s.actions.isEmpty()) {
                UiKit.criterion(list, R.drawable.ic_check, Tone.SUCCESS, "You're all caught up", null,
                        "No pending applications, rounds or preparation gaps right now. Check back as new drives open.");
                return;
            }
            android.util.TypedValue ripple = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            for (CareerActionEngine.Action a : s.actions) {
                int icon; Tone tone;
                switch (a.kind) {
                    case VERIFY: icon = R.drawable.ic_lock; tone = Tone.WARNING; break;
                    case STAGE: icon = R.drawable.ic_schedule; tone = Tone.PRIMARY; break;
                    case APPLY: icon = R.drawable.ic_work; tone = Tone.SUCCESS; break;
                    case SKILL: icon = R.drawable.ic_school; tone = Tone.PRIMARY; break;
                    case READINESS: icon = R.drawable.ic_trending; tone = Tone.PRIMARY; break;
                    default: icon = R.drawable.ic_person; tone = Tone.NEUTRAL;
                }
                View row = UiKit.criterion(list, icon, tone, a.title, null, a.why + "\nNext: " + a.next);
                row.setBackgroundResource(ripple.resourceId);
                row.setOnClickListener(x -> {
                    switch (a.kind) {
                        case STAGE: main().push(JourneyFragment.of(a.applicationId, false)); break;
                        case APPLY: main().push(DriveScreens.DetailFragment.of(a.driveId)); break;
                        case SKILL: case READINESS: main().selectTab(R.id.nav_readiness); break;
                        default: main().selectTab(R.id.nav_profile);
                    }
                });
            }
        }

        private void bindNotice(Resource<List<Notice>> r) {
            Notice latest = null;
            if (r != null && r.data != null) for (Notice n : r.data) if (Notice.ALL.equals(n.audience)) { latest = n; break; }
            ((TextView) root.findViewById(R.id.ann_title)).setText(latest == null ? "No announcements yet" : latest.title);
            UiKit.setOrHide(root.findViewById(R.id.ann_body), latest == null
                    ? "Announcements from the placement office will appear here." : latest.body);
            UiKit.setOrHide(root.findViewById(R.id.ann_time), latest == null ? null : UiKit.relative(latest.createdAt));
        }
    }

    // =====================================================================================
    public static class ApplicationsFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Application> adapter;

        @Override protected String title() { return getString(R.string.nav_applications); }

        @Override
        protected void setup() {
            tabs("All", "Active", "Selected", "Not selected");
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, a, pos) -> {
                StudentViewModel.Snapshot s = student().snapshot.getValue();
                DriveView dv = s == null ? null : s.find(a.driveId);
                String stage = dv != null && a.currentStage < dv.drive.stages.size() ? dv.drive.stages.get(a.currentStage).name : null;
                AppStatus st = a.statusEnum();
                UiKit.row(v, Models.initials(a.companyName), UiKit.tone(st), a.driveTitle, a.companyName,
                        UiKit.join(" · ", stage == null ? null : "Stage: " + stage, "Updated " + UiKit.relative(a.updatedAt)),
                        UiKit.label(st), UiKit.tone(st));
                v.setOnClickListener(x -> main().push(JourneyFragment.of(a.id, false)));
            });
            list.setAdapter(adapter);
            student().snapshot.observe(getViewLifecycleOwner(), s -> render());
            student().applications.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            StudentViewModel.Snapshot s = student().snapshot.getValue();
            List<Application> items = null;
            if (s != null) {
                items = new ArrayList<>();
                String tab = selectedTab();
                for (Application a : StudentViewModel.sortedApplications(s.applications)) {
                    AppStatus st = a.statusEnum();
                    if ("Active".equals(tab) && st.isFinal()) continue;
                    if ("Selected".equals(tab) && st != AppStatus.SELECTED) continue;
                    if ("Not selected".equals(tab) && st != AppStatus.REJECTED) continue;
                    items.add(a);
                }
            }
            UiKit.render(student().applications.getValue(), items, adapter, state, refresh, R.drawable.ic_assignment,
                    "No applications here", "Apply to drives you're eligible for and track every stage here.", this::render);
            if (items != null && items.isEmpty() && s.applications.isEmpty()) {
                state.show(R.drawable.ic_assignment, "No applications yet",
                        "Find drives you're eligible for and apply in one tap.", "Browse drives",
                        () -> main().selectTab(R.id.nav_drives));
            }
        }
    }

    // =====================================================================================
    /** Recruitment journey. Students see their own application; officers get Advance/Reject actions. */
    public static class JourneyFragment extends UiKit.Screen {
        private View root;
        private UiKit.StateView state;

        static JourneyFragment of(String appId, boolean officer) {
            JourneyFragment f = new JourneyFragment();
            Bundle b = new Bundle();
            b.putString("appId", appId);
            b.putString("officer", String.valueOf(officer));
            f.setArguments(b);
            return f;
        }

        private boolean officerMode() { return "true".equals(arg("officer")); }

        @Override protected String title() { return officerMode() ? "Applicant" : "Application journey"; }

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
            root = inflater.inflate(R.layout.fragment_journey, container, false);
            state = new UiKit.StateView(root.findViewById(R.id.state));
            state.loading();
            return root;
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle saved) {
            if (officerMode()) {
                OfficerViewModel vm = officer();
                vm.applications.observe(getViewLifecycleOwner(), r -> bindOfficer());
                vm.drives.observe(getViewLifecycleOwner(), r -> bindOfficer());
                vm.profiles.observe(getViewLifecycleOwner(), r -> bindOfficer());
            } else {
                student().snapshot.observe(getViewLifecycleOwner(), s -> {
                    if (s == null) return;
                    Application a = null;
                    for (Application x : s.applications) if (x.id.equals(arg("appId"))) a = x;
                    DriveView dv = a == null ? null : s.find(a.driveId);
                    bind(a, dv == null ? null : dv.drive, null);
                });
            }
        }

        private void bindOfficer() {
            OfficerViewModel vm = officer();
            Application a = vm.application(arg("appId"));
            bind(a, a == null ? null : vm.drive(a.driveId), a == null ? null : vm.profile(a.studentId));
        }

        private void bind(Application a, Drive d, StudentProfile live) {
            if (a == null || d == null) {
                state.empty(R.drawable.ic_assignment, "Application not found", "It may still be syncing. Check your connection.");
                return;
            }
            state.hide();
            AppStatus st = a.statusEnum();
            boolean officer = officerMode();
            ((TextView) root.findViewById(R.id.j_title)).setText(officer ? a.studentName : a.driveTitle);
            UiKit.badge(root.findViewById(R.id.j_badge), UiKit.label(st), UiKit.tone(st));
            ((TextView) root.findViewById(R.id.j_subtitle)).setText(officer
                    ? UiKit.join(" · ", a.studentRoll, a.driveTitle, a.companyName) : a.companyName);
            ((TextView) root.findViewById(R.id.j_meta)).setText(UiKit.join(" · ", "Applied " + UiKit.date(a.appliedAt),
                    "Match score " + a.matchScore + "%", "Updated " + UiKit.relative(a.updatedAt)));
            View openDrive = root.findViewById(R.id.j_open_drive);
            openDrive.setVisibility(officer ? View.GONE : View.VISIBLE);
            openDrive.setOnClickListener(x -> main().push(DriveScreens.DetailFragment.of(d.id)));
            ((TextView) root.findViewById(R.id.snapshot)).setText(String.format(Locale.US,
                    "CGPA %s · Branch %s\nActive backlogs %s · Batch of %s",
                    a.snapCgpa == null ? "—" : String.format(Locale.US, "%.2f", a.snapCgpa), a.snapBranch,
                    a.snapBacklogs, a.snapGraduationYear));
            ChipGroup chips = root.findViewById(R.id.profile_chips);
            LinearLayout links = root.findViewById(R.id.profile_links);
            chips.removeAllViews();
            links.removeAllViews();
            if (officer && live != null) {
                UiKit.chips(chips, live.skills, null);
                ChipGroup linkGroup = new ChipGroup(requireContext());
                links.addView(linkGroup);
                link(linkGroup, "Resume", live.resumeUrl);
                link(linkGroup, "LinkedIn", live.linkedinUrl);
                link(linkGroup, "GitHub", live.githubUrl);
            }
            timeline(root.findViewById(R.id.timeline), d, a);

            View actions = root.findViewById(R.id.officer_actions);
            actions.setVisibility(officer && !st.isFinal() ? View.VISIBLE : View.GONE);
            if (officer && !st.isFinal()) {
                int last = d.stages.size() - 1;
                String next = a.currentStage + 1 <= last ? d.stages.get(a.currentStage + 1).name : null;
                TextView advance = root.findViewById(R.id.advance);
                advance.setText(a.currentStage + 1 == last ? "Select" : "Advance");
                advance.setOnClickListener(x -> decide(a, d, true, next));
                root.findViewById(R.id.reject).setOnClickListener(x -> decide(a, d, false, null));
            }
        }

        private void link(ChipGroup g, String label, String url) {
            if (url == null) return;
            Chip c = UiKit.chip(g, label, false, false);
            c.setClickable(true);
            c.setChipIconResource(R.drawable.ic_link);
            c.setOnClickListener(x -> UiKit.openUrl(requireContext(), url));
        }

        private void decide(Application a, Drive d, boolean advance, String nextStage) {
            if (!requireOnline()) return;
            String title = advance ? (Models.LAST_STAGE.equals(nextStage) ? "Select " + a.studentName + "?" : "Move to “" + nextStage + "”?")
                    : "Reject " + a.studentName + "?";
            String msg = advance ? "The student is notified immediately. Add an optional note they will see on their timeline."
                    : "This is final. The student is notified and the application is closed.";
            UiKit.form(requireContext(), title, msg, new String[]{"Note (optional)"}, null,
                    new int[]{InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE},
                    advance ? "Confirm" : "Reject", (vals, f) -> {
                        Resource.Callback<Void> cb = r -> UiKit.snack(getView(), r.isSuccess()
                                ? "Updated and notified " + a.studentName : r.message);
                        if (advance) officer().advance(a, d, vals[0], cb);
                        else officer().reject(a, d, vals[0], cb);
                        return true;
                    });
        }
    }

    // =====================================================================================
    public static class NotificationsFragment extends UiKit.ListScreen {
        private UiKit.Adapter<Notice> adapter;

        @Override protected String title() { return getString(R.string.notifications); }

        @Override
        protected void setup() {
            tabs("All", "Application updates", "Announcements");
            adapter = new UiKit.Adapter<>(R.layout.item_row, (v, n, pos) -> {
                boolean personal = Notice.STUDENT.equals(n.audience);
                UiKit.row(v, personal ? "UP" : "AN", personal ? Tone.PRIMARY : Tone.NEUTRAL, n.title, n.body,
                        UiKit.relative(n.createdAt), personal ? "Update" : "Announcement", personal ? Tone.PRIMARY : Tone.NEUTRAL);
                ((TextView) v.findViewById(R.id.subtitle)).setMaxLines(6);
                if (n.applicationId != null) v.setOnClickListener(x -> main().push(JourneyFragment.of(n.applicationId, false)));
                else if (n.driveId != null) v.setOnClickListener(x -> main().push(DriveScreens.DetailFragment.of(n.driveId)));
            });
            list.setAdapter(adapter);
            student().notices.observe(getViewLifecycleOwner(), r -> render());
        }

        @Override
        protected void render() {
            if (adapter == null) return;
            Resource<List<Notice>> r = student().notices.getValue();
            List<Notice> items = null;
            if (r != null && r.data != null) {
                items = new ArrayList<>();
                String tab = selectedTab();
                for (Notice n : r.data) {
                    boolean personal = Notice.STUDENT.equals(n.audience);
                    if ("Application updates".equals(tab) && !personal) continue;
                    if ("Announcements".equals(tab) && personal) continue;
                    items.add(n);
                }
            }
            UiKit.render(r, items, adapter, state, refresh, R.drawable.ic_notifications, "You're all caught up",
                    "Application updates and announcements from the placement office appear here.", this::render);
        }
    }
}
