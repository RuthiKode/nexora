package com.nexora.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * All plain data types of NEXORA. Pure Java: no Android or Firebase imports so the
 * engines that consume these classes run on the plain JVM.
 */
public final class Models {
    private Models() {}

    public static final String ROLE_STUDENT = "STUDENT";
    public static final String ROLE_OFFICER = "OFFICER";

    public static final List<String> BRANCHES = Collections.unmodifiableList(Arrays.asList(
            "CSE", "CSE_AI", "IT", "ECE", "EEE", "ME", "CE", "CHE"));

    public static final String FIRST_STAGE = "Application Submitted";
    public static final String LAST_STAGE = "Final Outcome";

    public enum RoleCategory {
        SDE("Software Engineering"), DATA("Data"), ML_AI("ML / AI"), CORE("Core Engineering"),
        ANALYST("Analyst"), QA("Quality Assurance"), OTHER("Other");
        public final String label;
        RoleCategory(String label) { this.label = label; }
    }

    public enum JobType {
        FULL_TIME("Full-time"), INTERNSHIP("Internship"), INTERN_PPO("Internship + PPO");
        public final String label;
        JobType(String label) { this.label = label; }
    }

    public enum DriveStatus { DRAFT, OPEN, CLOSED }

    public enum AppStatus {
        SUBMITTED, IN_PROGRESS, SELECTED, REJECTED;
        public boolean isFinal() { return this == SELECTED || this == REJECTED; }
    }

    /** Readiness components. The last four are also activity types and drive prep-focus values. */
    public enum Component {
        RESUME("Resume completeness", 20), APTITUDE("Aptitude", 25), CODING("Coding", 25),
        INTERVIEW("Mock interviews", 15), SKILLS("Skill development", 15);
        public final String label;
        public final int weight;
        Component(String label, int weight) { this.label = label; this.weight = weight; }
        public static final List<Component> ACTIVITY_TYPES =
                Collections.unmodifiableList(Arrays.asList(APTITUDE, CODING, INTERVIEW, SKILLS));
    }

    public static class StudentProfile {
        public String uid, name, email;
        public String rollNumber, branch;
        public Double cgpa;
        public Integer activeBacklogs, graduationYear;
        public boolean academicVerified;
        public String verifiedBy;
        public Date verifiedAt, updatedAt;
        public List<String> skills = new ArrayList<>();
        public List<String> preferredRoles = new ArrayList<>();
        public List<String> certifications = new ArrayList<>();
        public String resumeUrl, summary, linkedinUrl, githubUrl;
    }

    public static class Company {
        public String id, name, sector, website, description, logoInitials, createdBy;
        public Date createdAt;
    }

    public static class Stage {
        public String name, type, venueOrLink;
        public Date scheduledAt;

        public Stage() {}
        public Stage(String name, String type) { this.name = name; this.type = type; }
    }

    public static class Drive {
        public String id, companyId, companyName, title, location, description, createdBy;
        public String roleCategory = RoleCategory.SDE.name();
        public String jobType = JobType.FULL_TIME.name();
        public String status = DriveStatus.DRAFT.name();
        public double packageLpa;
        public double minCgpa;
        public int maxActiveBacklogs;
        public List<String> eligibleBranches = new ArrayList<>();
        public List<Integer> graduationYears = new ArrayList<>();
        public List<String> requiredSkills = new ArrayList<>();
        public List<String> prepFocus = new ArrayList<>();
        public List<Stage> stages = new ArrayList<>();
        public Date deadline, createdAt, updatedAt;
        public int applicantCount;

        public boolean isOpen(long now) {
            return DriveStatus.OPEN.name().equals(status) && deadline != null && now < deadline.getTime();
        }

        public static List<Stage> defaultStages() {
            List<Stage> s = new ArrayList<>();
            s.add(new Stage(FIRST_STAGE, "SUBMISSION"));
            s.add(new Stage("Online Assessment", "TEST"));
            s.add(new Stage("Technical Interview", "INTERVIEW"));
            s.add(new Stage("HR Interview", "INTERVIEW"));
            s.add(new Stage(LAST_STAGE, "OUTCOME"));
            return s;
        }
    }

    public static class StageRecord {
        public static final String PASSED = "PASSED", FAILED = "FAILED";
        public int stageIndex;
        public String result, note;
        public Date updatedAt;

        public StageRecord() {}
        public StageRecord(int stageIndex, String result, String note, Date updatedAt) {
            this.stageIndex = stageIndex; this.result = result; this.note = note; this.updatedAt = updatedAt;
        }
    }

    public static class Application {
        public String id, driveId, studentId, driveTitle, companyName, studentName, studentRoll;
        // profileSnapshot at apply time
        public Double snapCgpa;
        public String snapBranch;
        public Integer snapBacklogs, snapGraduationYear;
        public int matchScore;
        public String status = AppStatus.SUBMITTED.name();
        public int currentStage;
        public List<StageRecord> stageHistory = new ArrayList<>();
        public Date appliedAt, updatedAt;

        public AppStatus statusEnum() { return AppStatus.valueOf(status); }

        public Application copy() {
            Application a = new Application();
            a.id = id; a.driveId = driveId; a.studentId = studentId; a.driveTitle = driveTitle;
            a.companyName = companyName; a.studentName = studentName; a.studentRoll = studentRoll;
            a.snapCgpa = snapCgpa; a.snapBranch = snapBranch; a.snapBacklogs = snapBacklogs;
            a.snapGraduationYear = snapGraduationYear; a.matchScore = matchScore; a.status = status;
            a.currentStage = currentStage; a.stageHistory = new ArrayList<>(stageHistory);
            a.appliedAt = appliedAt; a.updatedAt = updatedAt;
            return a;
        }
    }

    public static class Activity {
        public String id, type, title;
        public Integer scorePercent, rating;
        public int durationMin;
        public Date date, createdAt;
    }

    public static class Notice {
        public static final String ALL = "ALL", STUDENT = "STUDENT";
        public String id, title, body, audience, targetUid, driveId, applicationId, createdBy;
        public Date createdAt;
    }

    /** Deterministic application id: one application per student per drive. */
    public static String applicationId(String driveId, String studentUid) {
        return driveId + "_" + studentUid;
    }

    public static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        String s = parts[0].substring(0, 1);
        if (parts.length > 1) s += parts[parts.length - 1].charAt(0);
        return s.toUpperCase();
    }
}
