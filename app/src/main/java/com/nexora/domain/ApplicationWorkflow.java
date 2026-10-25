package com.nexora.domain;

import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StageRecord;
import com.nexora.domain.Models.StudentProfile;

import java.util.Date;

/**
 * Application lifecycle. canApply is a client-side pre-check for user feedback only;
 * Firestore rules are the real gate. Transitions: SUBMITTED → IN_PROGRESS → … → SELECTED,
 * any non-final state → REJECTED. Final states are immutable.
 */
public final class ApplicationWorkflow {

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        Decision(boolean allowed, String reason) { this.allowed = allowed; this.reason = reason; }
    }

    public Decision canApply(EligibilityEngine.Result eligibility, Drive d, Application existing, long now) {
        if (existing != null) return new Decision(false, "You have already applied to this drive.");
        if (!Models.DriveStatus.OPEN.name().equals(d.status)) {
            return new Decision(false, "Applications closed — this drive is no longer accepting applications.");
        }
        if (d.deadline == null || now >= d.deadline.getTime()) {
            return new Decision(false, "Applications closed — the deadline has passed.");
        }
        switch (eligibility.status) {
            case NOT_ELIGIBLE:
                return new Decision(false, "You do not meet the eligibility criteria for this drive.");
            case NEEDS_VERIFICATION:
                return new Decision(false, "Your academic record must be complete and verified before you can apply.");
            default:
                return new Decision(true, null);
        }
    }

    /** Builds the application; the repository sets appliedAt/updatedAt to the server timestamp. */
    public Application newApplication(StudentProfile p, Drive d, int matchScore) {
        Application a = new Application();
        a.id = Models.applicationId(d.id, p.uid);
        a.driveId = d.id;
        a.studentId = p.uid;
        a.driveTitle = d.title;
        a.companyName = d.companyName;
        a.studentName = p.name;
        a.studentRoll = p.rollNumber;
        a.snapCgpa = p.cgpa;
        a.snapBranch = p.branch;
        a.snapBacklogs = p.activeBacklogs;
        a.snapGraduationYear = p.graduationYear;
        a.matchScore = Math.max(0, Math.min(100, matchScore));
        a.status = AppStatus.SUBMITTED.name();
        a.currentStage = 0;
        return a;
    }

    /** Marks the current stage PASSED and moves on; reaching "Final Outcome" means SELECTED. */
    public Application advance(Application app, Drive d, String note, Date now) {
        requireActive(app);
        int last = d.stages.size() - 1;
        if (last < 1 || app.currentStage >= last) {
            throw new IllegalStateException("Applicant is already at the final stage.");
        }
        Application next = app.copy();
        next.stageHistory.add(new StageRecord(app.currentStage, StageRecord.PASSED, blankToNull(note), now));
        next.currentStage = app.currentStage + 1;
        next.status = next.currentStage == last ? AppStatus.SELECTED.name() : AppStatus.IN_PROGRESS.name();
        next.updatedAt = now;
        return next;
    }

    public Application reject(Application app, String note, Date now) {
        requireActive(app);
        Application next = app.copy();
        next.stageHistory.add(new StageRecord(app.currentStage, StageRecord.FAILED, blankToNull(note), now));
        next.status = AppStatus.REJECTED.name();
        next.updatedAt = now;
        return next;
    }

    public static boolean isAllowedTransition(AppStatus from, AppStatus to) {
        if (from.isFinal()) return false;
        switch (to) {
            case IN_PROGRESS: case SELECTED: case REJECTED: return true;
            default: return false;
        }
    }

    private static void requireActive(Application app) {
        if (app.statusEnum().isFinal()) {
            throw new IllegalStateException("Application is already " + app.status + " and cannot change.");
        }
    }

    private static String blankToNull(String s) { return EligibilityEngine.isBlank(s) ? null : s.trim(); }

    /** Notification text sent to the student after an officer decision. */
    public static String updateMessage(Application a, Drive d) {
        String stage = a.currentStage < d.stages.size() ? d.stages.get(a.currentStage).name : "next stage";
        switch (a.statusEnum()) {
            case SELECTED: return "Congratulations! You have been selected for " + a.driveTitle + " at " + a.companyName + ".";
            case REJECTED: return "Your application for " + a.driveTitle + " at " + a.companyName + " was not taken forward.";
            default: return "You have moved to \"" + stage + "\" for " + a.driveTitle + " at " + a.companyName + ".";
        }
    }
}
