package com.nexora.ui;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nexora.NexoraApp;
import com.nexora.data.Resource;
import com.nexora.domain.ApplicationWorkflow;
import com.nexora.domain.Models;
import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Company;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;
import com.nexora.domain.Models.StudentProfile;
import com.nexora.domain.ReadinessAnalyzer;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Activity-scoped state and actions for the placement officer portal. */
public class OfficerViewModel extends AndroidViewModel {
    private final NexoraApp.AppContainer c;
    public final String uid;
    public final LiveData<Resource<List<Drive>>> drives;
    public final LiveData<Resource<List<Company>>> companies;
    public final LiveData<Resource<List<StudentProfile>>> profiles;
    public final LiveData<Resource<List<Application>>> applications;
    public final LiveData<Resource<List<Notice>>> notices;
    /** Readiness report per student uid, loaded on demand for analytics. */
    public final MutableLiveData<Resource<Map<String, ReadinessAnalyzer.Report>>> readiness = new MutableLiveData<>();

    public OfficerViewModel(@NonNull android.app.Application app) {
        super(app);
        c = NexoraApp.container(app);
        uid = c.auth.uid();
        drives = c.placement.allDrives();
        companies = c.placement.companies();
        profiles = c.auth.allProfiles();
        applications = c.placement.allApplications();
        notices = c.placement.allNotices();
    }

    public NexoraApp.AppContainer container() { return c; }

    // ---------------------------------------------------------------- lookups

    public static <T> List<T> data(LiveData<Resource<List<T>>> ld) {
        Resource<List<T>> r = ld.getValue();
        return r == null || r.data == null ? new ArrayList<>() : r.data;
    }

    public Drive drive(String id) {
        for (Drive d : data(drives)) if (d.id.equals(id)) return d;
        return null;
    }

    public StudentProfile profile(String studentUid) {
        for (StudentProfile p : data(profiles)) if (p.uid.equals(studentUid)) return p;
        return null;
    }

    public Application application(String id) {
        for (Application a : data(applications)) if (a.id.equals(id)) return a;
        return null;
    }

    public List<Application> applicationsFor(String driveId) {
        List<Application> out = new ArrayList<>();
        for (Application a : data(applications)) if (driveId.equals(a.driveId)) out.add(a);
        return out;
    }

    // ---------------------------------------------------------------- actions

    public void setVerified(String studentUid, boolean verified, Resource.Callback<Void> cb) {
        c.auth.setVerified(studentUid, verified, cb);
    }

    public void saveCompany(Company co, Resource.Callback<Void> cb) { c.placement.saveCompany(co, uid, cb); }

    public void saveDrive(Drive d, Resource.Callback<String> cb) { c.placement.saveDrive(d, uid, cb); }

    public void setDriveStatus(String driveId, Models.DriveStatus s, Resource.Callback<Void> cb) {
        c.placement.setDriveStatus(driveId, s, cb);
    }

    public void syncApplicantCount(Drive d, int count) {
        if (d.applicantCount != count) c.placement.syncApplicantCount(d.id, count);
    }

    public void advance(Application a, Drive d, String note, Resource.Callback<Void> cb) {
        decide(c.workflow.advance(a, d, note, new Date()), d, cb);
    }

    public void reject(Application a, Drive d, String note, Resource.Callback<Void> cb) {
        decide(c.workflow.reject(a, note, new Date()), d, cb);
    }

    /** Application update + targeted notification in one batch. */
    private void decide(Application updated, Drive d, Resource.Callback<Void> cb) {
        Notice n = new Notice();
        n.title = updated.companyName + " · " + updated.driveTitle;
        n.body = ApplicationWorkflow.updateMessage(updated, d);
        Models.Stage stage = updated.currentStage < d.stages.size() ? d.stages.get(updated.currentStage) : null;
        if (stage != null && stage.scheduledAt != null && !Models.AppStatus.valueOf(updated.status).isFinal()) {
            n.body += " Scheduled: " + UiKit.dateTime(stage.scheduledAt)
                    + (stage.venueOrLink == null ? "" : " · " + stage.venueOrLink) + ".";
        }
        n.audience = Notice.STUDENT;
        n.targetUid = updated.studentId;
        n.driveId = updated.driveId;
        n.applicationId = updated.id;
        n.createdBy = uid;
        c.placement.updateApplication(updated, n, cb);
    }

    public void announce(String title, String body, Resource.Callback<Void> cb) {
        c.placement.publishAnnouncement(title, body, uid, null, cb);
    }

    /** Broadcast for a newly opened drive; tapping the notification opens the drive. Best effort. */
    public void announceDrive(Drive d, String driveId) {
        String title = "New drive: " + d.title + " · " + d.companyName;
        String body = UiKit.join(" · ", UiKit.lpa(d.packageLpa), d.location,
                d.deadline == null ? null : "Apply by " + UiKit.date(d.deadline))
                + ". Open NEXORA to check your eligibility and match score.";
        c.placement.publishAnnouncement(title, body, uid, driveId, r -> { });
    }

    /**
     * Loads every student's activities and computes their readiness report with the existing analyzer.
     * Used for applicant/selected averages and aggregate preparation insights.
     */
    public void loadReadiness() {
        List<String> ids = new ArrayList<>();
        for (StudentProfile p : data(profiles)) ids.add(p.uid);
        if (ids.isEmpty()) {
            readiness.setValue(Resource.success(new HashMap<>()));
            return;
        }
        readiness.setValue(Resource.loading(null));
        c.readiness.fetchFor(ids)
                .addOnSuccessListener(byStudent -> {
                    Map<String, ReadinessAnalyzer.Report> out = new HashMap<>();
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, List<Activity>> e : byStudent.entrySet()) {
                        StudentProfile p = profile(e.getKey());
                        if (p != null) out.put(e.getKey(), c.readinessAnalyzer.analyze(p, e.getValue(), new ArrayList<>(), now));
                    }
                    readiness.setValue(Resource.success(out));
                })
                .addOnFailureListener(e -> readiness.setValue(Resource.error(e.getMessage(), null)));
    }
}
