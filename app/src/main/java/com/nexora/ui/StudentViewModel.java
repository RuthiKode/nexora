package com.nexora.ui;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.nexora.NexoraApp;
import com.nexora.data.Resource;
import com.nexora.data.SyncWorker;
import com.nexora.domain.CareerActionEngine;
import com.nexora.domain.EligibilityEngine;
import com.nexora.domain.MatchScoreCalculator;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Company;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;
import com.nexora.domain.Models.StudentProfile;
import com.nexora.domain.ReadinessAnalyzer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Activity-scoped state for the student portal. Observes the live repositories and recomputes the
 * explainable eligibility, match score and readiness report whenever any input changes.
 */
public class StudentViewModel extends AndroidViewModel {

    /** A drive together with everything the student needs to decide about it (engine results + UI state). */
    public static final class DriveView extends CareerActionEngine.Opportunity {
        public Company company;
        public boolean bookmarked;
    }

    public static final class Snapshot {
        public StudentProfile profile;
        public final List<DriveView> drives = new ArrayList<>();
        public ReadinessAnalyzer.Report readiness;
        public List<Application> applications = new ArrayList<>();
        public List<Activity> activities = new ArrayList<>();
        /** Career Action Plan, recomputed from the same inputs whenever any of them changes. */
        public List<CareerActionEngine.Action> actions = new ArrayList<>();
        public boolean drivesFromCache;

        public DriveView find(String driveId) {
            for (DriveView v : drives) if (v.drive.id.equals(driveId)) return v;
            return null;
        }
    }

    /** Drive search/filter/sort state. */
    public static final class Filter {
        public static final String SORT_MATCH = "MATCH", SORT_DEADLINE = "DEADLINE", SORT_PACKAGE = "PACKAGE";
        public String query = "", location = "", sort = SORT_MATCH;
        public Set<String> jobTypes = new HashSet<>(), roles = new HashSet<>();
        public int minPackage;
        public boolean eligibleOnly, bookmarkedOnly, openOnly;

        public int activeCount() {
            int n = jobTypes.size() + roles.size();
            if (!location.isEmpty()) n++;
            if (minPackage > 0) n++;
            if (eligibleOnly) n++;
            if (bookmarkedOnly) n++;
            if (openOnly) n++;
            return n;
        }

        public List<DriveView> apply(List<DriveView> in, long now) {
            String q = query.toLowerCase(Locale.ROOT).trim();
            String loc = location.toLowerCase(Locale.ROOT).trim();
            List<DriveView> out = new ArrayList<>();
            for (DriveView v : in) {
                Drive d = v.drive;
                if (!q.isEmpty() && !(contains(d.title, q) || contains(d.companyName, q) || contains(d.location, q)
                        || contains(MatchScoreCalculator.normalizeSkills(d.requiredSkills).toString(), q))) continue;
                if (!jobTypes.isEmpty() && !jobTypes.contains(d.jobType)) continue;
                if (!roles.isEmpty() && !roles.contains(d.roleCategory)) continue;
                if (!loc.isEmpty() && !contains(d.location, loc)) continue;
                if (d.packageLpa < minPackage) continue;
                if (eligibleOnly && v.eligibility.status != EligibilityEngine.Status.ELIGIBLE) continue;
                if (bookmarkedOnly && !v.bookmarked) continue;
                if (openOnly && !d.isOpen(now)) continue;
                out.add(v);
            }
            Comparator<DriveView> byDeadline = Comparator.comparingLong(v -> v.drive.deadline == null ? Long.MAX_VALUE : v.drive.deadline.getTime());
            switch (sort) {
                case SORT_PACKAGE:
                    out.sort(Comparator.comparingDouble((DriveView v) -> -v.drive.packageLpa).thenComparing(byDeadline));
                    break;
                case SORT_DEADLINE:
                    out.sort(Comparator.comparing((DriveView v) -> !v.drive.isOpen(now)).thenComparing(byDeadline));
                    break;
                default:
                    out.sort(Comparator.comparing((DriveView v) -> !v.drive.isOpen(now))
                            .thenComparingInt(v -> v.match == null ? 1 : -v.match.score - 1000)
                            .thenComparing(byDeadline));
            }
            return out;
        }

        private static boolean contains(String hay, String needle) {
            return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private final NexoraApp.AppContainer c;
    public final String uid;
    public final LiveData<Resource<StudentProfile>> profile;
    public final LiveData<Resource<List<Drive>>> drives;
    public final LiveData<Resource<List<Application>>> applications;
    public final LiveData<Resource<List<Notice>>> notices;
    public final LiveData<Resource<List<Company>>> companies;
    private final LiveData<List<String>> bookmarks;
    private final LiveData<List<Activity>> activities;
    public final MediatorLiveData<Snapshot> snapshot = new MediatorLiveData<>();
    public final MutableLiveData<Filter> filter = new MutableLiveData<>(new Filter());

    public StudentViewModel(@NonNull android.app.Application app) {
        super(app);
        c = NexoraApp.container(app);
        uid = c.auth.uid();
        profile = c.auth.profile(uid);
        drives = c.placement.studentDrives();
        applications = c.placement.myApplications(uid);
        notices = c.placement.studentNotices(uid);
        companies = c.placement.companies();
        bookmarks = c.placement.bookmarks(uid);
        activities = c.readiness.activities(uid);
        for (LiveData<?> src : new LiveData<?>[]{profile, drives, applications, companies, bookmarks, activities}) {
            snapshot.addSource(src, x -> recompute());
        }
    }

    private void recompute() {
        Resource<StudentProfile> pr = profile.getValue();
        if (pr == null || pr.data == null) return;
        long now = System.currentTimeMillis();
        Snapshot s = new Snapshot();
        s.profile = pr.data;
        List<Drive> ds = data(drives.getValue());
        s.drivesFromCache = drives.getValue() != null && drives.getValue().fromCache;
        s.applications = data(applications.getValue());
        s.activities = activities.getValue() == null ? new ArrayList<>() : activities.getValue();
        Set<String> marks = new HashSet<>(bookmarks.getValue() == null ? new ArrayList<>() : bookmarks.getValue());
        Map<String, Application> appByDrive = new HashMap<>();
        for (Application a : s.applications) appByDrive.put(a.driveId, a);
        Map<String, Company> companyById = new HashMap<>();
        for (Company co : data(companies.getValue())) companyById.put(co.id, co);

        List<Drive> eligibleOpen = new ArrayList<>();
        for (Drive d : ds) {
            DriveView v = new DriveView();
            v.drive = d;
            v.company = companyById.get(d.companyId);
            v.eligibility = c.eligibility.evaluate(s.profile, d);
            v.application = appByDrive.get(d.id);
            v.bookmarked = marks.contains(d.id);
            v.decision = c.workflow.canApply(v.eligibility, d, v.application, now);
            if (MatchScoreCalculator.applies(v.eligibility) && d.isOpen(now)) eligibleOpen.add(d);
            s.drives.add(v);
        }
        s.readiness = c.readinessAnalyzer.analyze(s.profile, s.activities, eligibleOpen, now);
        for (DriveView v : s.drives) {
            if (!MatchScoreCalculator.applies(v.eligibility)) continue;
            v.match = c.match.calculate(s.profile, v.drive, s.readiness.percents, s.readiness.overall);
            v.match.provisional = v.eligibility.status != EligibilityEngine.Status.ELIGIBLE;
        }
        s.actions = c.careerActions.plan(s.profile, s.drives, s.readiness, now);
        snapshot.setValue(s);
    }

    private static <T> List<T> data(Resource<List<T>> r) {
        return r == null || r.data == null ? new ArrayList<>() : r.data;
    }

    // ---------------------------------------------------------------- actions

    public void apply(DriveView v, Resource.Callback<Void> cb) {
        Snapshot s = snapshot.getValue();
        if (s == null) return;
        // Re-check on the latest snapshot; Firestore rules are still the real gate.
        if (!v.decision.allowed) {
            cb.onResult(Resource.error(v.decision.reason, null));
            return;
        }
        Application a = c.workflow.newApplication(s.profile, v.drive, v.match == null ? 0 : v.match.score);
        c.placement.apply(a, cb);
    }

    public void toggleBookmark(String driveId) { c.placement.toggleBookmark(uid, driveId); }

    public void saveProfile(StudentProfile p, Resource.Callback<Void> cb) { c.auth.saveProfile(p, cb); }

    /** Saved to Room immediately (works offline); a network-constrained sync uploads it. */
    public void logActivity(Activity a) {
        c.readiness.log(uid, a, () -> SyncWorker.syncSoon(getApplication()));
    }

    public void setFilter(Filter f) { filter.setValue(f); }

    public Filter currentFilter() { return filter.getValue() == null ? new Filter() : filter.getValue(); }

    /** Applications sorted with active ones first. */
    public static List<Application> sortedApplications(List<Application> apps) {
        List<Application> out = new ArrayList<>(apps);
        out.sort(Comparator.comparing((Application a) -> Models.AppStatus.valueOf(a.status).isFinal())
                .thenComparing(a -> a.updatedAt == null ? 0L : -a.updatedAt.getTime()));
        return out;
    }
}
