package com.nexora.data;

import androidx.lifecycle.LiveData;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Company;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Notice;
import com.nexora.domain.Models.Stage;
import com.nexora.domain.Models.StageRecord;
import com.nexora.domain.Models.StudentProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The single place where Firestore documents (and their JSON cache copies) are converted to and
 * from domain models, plus collection names and a lifecycle-aware snapshot-listener LiveData.
 */
public final class FirestoreMapper {
    private FirestoreMapper() {}

    public static final String USERS = "users", PROFILES = "studentProfiles", COMPANIES = "companies",
            DRIVES = "placementDrives", APPLICATIONS = "applications", READINESS = "readinessProgress",
            ACTIVITIES = "activities", NOTIFICATIONS = "notifications";

    public static final String[] ACADEMIC_FIELDS = {"rollNumber", "branch", "cgpa", "activeBacklogs", "graduationYear"};

    public interface Parser<T> { T parse(String id, Map<String, Object> data); }

    // ---------------------------------------------------------------- profiles

    public static StudentProfile profile(String id, Map<String, Object> m) {
        StudentProfile p = new StudentProfile();
        p.uid = id;
        p.name = str(m.get("name"));
        p.email = str(m.get("email"));
        p.rollNumber = str(m.get("rollNumber"));
        p.branch = str(m.get("branch"));
        p.cgpa = dbl(m.get("cgpa"));
        p.activeBacklogs = integer(m.get("activeBacklogs"));
        p.graduationYear = integer(m.get("graduationYear"));
        p.academicVerified = Boolean.TRUE.equals(m.get("academicVerified"));
        p.verifiedBy = str(m.get("verifiedBy"));
        p.verifiedAt = date(m.get("verifiedAt"));
        p.skills = strings(m.get("skills"));
        p.preferredRoles = strings(m.get("preferredRoles"));
        p.certifications = strings(m.get("certifications"));
        p.resumeUrl = str(m.get("resumeUrl"));
        p.summary = str(m.get("summary"));
        p.linkedinUrl = str(m.get("linkedinUrl"));
        p.githubUrl = str(m.get("githubUrl"));
        p.updatedAt = date(m.get("updatedAt"));
        return p;
    }

    /** Fields a student may write. Academic fields are omitted once the record is verified. */
    public static Map<String, Object> profileMap(StudentProfile p, boolean includeAcademic) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", p.name);
        m.put("email", p.email);
        if (includeAcademic) {
            m.put("rollNumber", p.rollNumber);
            m.put("branch", p.branch);
            m.put("cgpa", p.cgpa);
            m.put("activeBacklogs", p.activeBacklogs);
            m.put("graduationYear", p.graduationYear);
        }
        m.put("skills", p.skills);
        m.put("preferredRoles", p.preferredRoles);
        m.put("certifications", p.certifications);
        m.put("resumeUrl", p.resumeUrl);
        m.put("summary", p.summary);
        m.put("linkedinUrl", p.linkedinUrl);
        m.put("githubUrl", p.githubUrl);
        return m;
    }

    // ---------------------------------------------------------------- companies

    public static Company company(String id, Map<String, Object> m) {
        Company c = new Company();
        c.id = id;
        c.name = str(m.get("name"));
        c.sector = str(m.get("sector"));
        c.website = str(m.get("website"));
        c.description = str(m.get("description"));
        c.logoInitials = str(m.get("logoInitials"));
        c.createdBy = str(m.get("createdBy"));
        c.createdAt = date(m.get("createdAt"));
        return c;
    }

    public static Map<String, Object> companyMap(Company c) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", c.name);
        m.put("sector", c.sector);
        m.put("website", c.website);
        m.put("description", c.description);
        m.put("logoInitials", c.logoInitials);
        return m;
    }

    // ---------------------------------------------------------------- drives

    @SuppressWarnings("unchecked")
    public static Drive drive(String id, Map<String, Object> m) {
        Drive d = new Drive();
        d.id = id;
        d.companyId = str(m.get("companyId"));
        d.companyName = str(m.get("companyName"));
        d.title = str(m.get("title"));
        d.roleCategory = orDefault(str(m.get("roleCategory")), d.roleCategory);
        d.jobType = orDefault(str(m.get("jobType")), d.jobType);
        d.location = str(m.get("location"));
        d.packageLpa = num(m.get("packageLpa"));
        d.description = str(m.get("description"));
        Map<String, Object> c = m.get("criteria") instanceof Map ? (Map<String, Object>) m.get("criteria") : new HashMap<>();
        d.minCgpa = num(c.get("minCgpa"));
        d.eligibleBranches = strings(c.get("eligibleBranches"));
        d.maxActiveBacklogs = (int) num(c.get("maxActiveBacklogs"));
        d.graduationYears = new ArrayList<>();
        if (c.get("graduationYears") instanceof List) {
            for (Object o : (List<Object>) c.get("graduationYears")) if (o instanceof Number) d.graduationYears.add(((Number) o).intValue());
        }
        d.requiredSkills = strings(m.get("requiredSkills"));
        d.prepFocus = strings(m.get("prepFocus"));
        d.stages = new ArrayList<>();
        if (m.get("stages") instanceof List) {
            for (Object o : (List<Object>) m.get("stages")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> sm = (Map<String, Object>) o;
                Stage s = new Stage(str(sm.get("name")), str(sm.get("type")));
                s.scheduledAt = date(sm.get("scheduledAt"));
                s.venueOrLink = str(sm.get("venueOrLink"));
                d.stages.add(s);
            }
        }
        d.deadline = date(m.get("deadline"));
        d.status = orDefault(str(m.get("status")), d.status);
        d.applicantCount = (int) num(m.get("applicantCount"));
        d.createdBy = str(m.get("createdBy"));
        d.createdAt = date(m.get("createdAt"));
        d.updatedAt = date(m.get("updatedAt"));
        return d;
    }

    public static Map<String, Object> driveMap(Drive d) {
        Map<String, Object> m = new HashMap<>();
        m.put("companyId", d.companyId);
        m.put("companyName", d.companyName);
        m.put("title", d.title);
        m.put("roleCategory", d.roleCategory);
        m.put("jobType", d.jobType);
        m.put("location", d.location);
        m.put("packageLpa", d.packageLpa);
        m.put("description", d.description);
        Map<String, Object> c = new HashMap<>();
        c.put("minCgpa", d.minCgpa);
        c.put("eligibleBranches", d.eligibleBranches);
        c.put("maxActiveBacklogs", d.maxActiveBacklogs);
        c.put("graduationYears", d.graduationYears);
        m.put("criteria", c);
        m.put("requiredSkills", d.requiredSkills);
        m.put("prepFocus", d.prepFocus);
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Stage s : d.stages) {
            Map<String, Object> sm = new HashMap<>();
            sm.put("name", s.name);
            sm.put("type", s.type);
            sm.put("scheduledAt", s.scheduledAt);
            sm.put("venueOrLink", s.venueOrLink);
            stages.add(sm);
        }
        m.put("stages", stages);
        m.put("deadline", d.deadline);
        m.put("status", d.status);
        m.put("applicantCount", d.applicantCount);
        return m;
    }

    // ---------------------------------------------------------------- applications

    @SuppressWarnings("unchecked")
    public static Application application(String id, Map<String, Object> m) {
        Application a = new Application();
        a.id = id;
        a.driveId = str(m.get("driveId"));
        a.studentId = str(m.get("studentId"));
        a.driveTitle = str(m.get("driveTitle"));
        a.companyName = str(m.get("companyName"));
        a.studentName = str(m.get("studentName"));
        a.studentRoll = str(m.get("studentRoll"));
        if (m.get("profileSnapshot") instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) m.get("profileSnapshot");
            a.snapCgpa = dbl(s.get("cgpa"));
            a.snapBranch = str(s.get("branch"));
            a.snapBacklogs = integer(s.get("activeBacklogs"));
            a.snapGraduationYear = integer(s.get("graduationYear"));
        }
        a.matchScore = (int) num(m.get("matchScore"));
        a.status = orDefault(str(m.get("status")), a.status);
        a.currentStage = (int) num(m.get("currentStage"));
        a.stageHistory = new ArrayList<>();
        if (m.get("stageHistory") instanceof List) {
            for (Object o : (List<Object>) m.get("stageHistory")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> h = (Map<String, Object>) o;
                a.stageHistory.add(new StageRecord((int) num(h.get("stageIndex")), str(h.get("result")),
                        str(h.get("note")), date(h.get("updatedAt"))));
            }
        }
        a.appliedAt = date(m.get("appliedAt"));
        a.updatedAt = date(m.get("updatedAt"));
        return a;
    }

    /** Full application document (timestamps are added by the repository as server timestamps). */
    public static Map<String, Object> applicationMap(Application a) {
        Map<String, Object> m = new HashMap<>();
        m.put("driveId", a.driveId);
        m.put("studentId", a.studentId);
        m.put("driveTitle", a.driveTitle);
        m.put("companyName", a.companyName);
        m.put("studentName", a.studentName);
        m.put("studentRoll", a.studentRoll);
        Map<String, Object> s = new HashMap<>();
        s.put("cgpa", a.snapCgpa);
        s.put("branch", a.snapBranch);
        s.put("activeBacklogs", a.snapBacklogs);
        s.put("graduationYear", a.snapGraduationYear);
        m.put("profileSnapshot", s);
        m.put("matchScore", a.matchScore);
        m.put("status", a.status);
        m.put("currentStage", a.currentStage);
        m.put("stageHistory", historyList(a.stageHistory));
        return m;
    }

    public static List<Map<String, Object>> historyList(List<StageRecord> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageRecord r : history) {
            Map<String, Object> h = new HashMap<>();
            h.put("stageIndex", r.stageIndex);
            h.put("result", r.result);
            h.put("note", r.note);
            h.put("updatedAt", r.updatedAt);
            out.add(h);
        }
        return out;
    }

    // ---------------------------------------------------------------- activities & notices

    public static Activity activity(String id, Map<String, Object> m) {
        Activity a = new Activity();
        a.id = id;
        a.type = str(m.get("type"));
        a.title = str(m.get("title"));
        a.scorePercent = integer(m.get("scorePercent"));
        a.rating = integer(m.get("rating"));
        a.durationMin = (int) num(m.get("durationMin"));
        a.date = date(m.get("date"));
        a.createdAt = date(m.get("createdAt"));
        return a;
    }

    public static Map<String, Object> activityMap(Activity a) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", a.type);
        m.put("title", a.title);
        m.put("scorePercent", a.scorePercent);
        m.put("rating", a.rating);
        m.put("durationMin", a.durationMin);
        m.put("date", a.date);
        m.put("createdAt", a.createdAt);
        return m;
    }

    public static Notice notice(String id, Map<String, Object> m) {
        Notice n = new Notice();
        n.id = id;
        n.title = str(m.get("title"));
        n.body = str(m.get("body"));
        n.audience = str(m.get("audience"));
        n.targetUid = str(m.get("targetUid"));
        n.driveId = str(m.get("driveId"));
        n.applicationId = str(m.get("applicationId"));
        n.createdBy = str(m.get("createdBy"));
        n.createdAt = date(m.get("createdAt"));
        return n;
    }

    public static Map<String, Object> noticeMap(Notice n) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", n.title);
        m.put("body", n.body);
        m.put("audience", n.audience);
        m.put("targetUid", n.targetUid);
        m.put("driveId", n.driveId);
        m.put("applicationId", n.applicationId);
        m.put("createdBy", n.createdBy);
        return m;
    }

    // ---------------------------------------------------------------- JSON (Room cache)

    /** Serialises a document map for the Room cache; dates become epoch millis. */
    public static String toJson(Map<String, Object> map) {
        return ((JSONObject) jsonValue(map)).toString();
    }

    public static Map<String, Object> fromJson(String json) {
        try {
            return toMap(new JSONObject(json));
        } catch (JSONException e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object jsonValue(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Date) return ((Date) v).getTime();
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                try { o.put(e.getKey(), jsonValue(e.getValue())); } catch (JSONException ignored) { }
            }
            return o;
        }
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object o : (List<Object>) v) a.put(jsonValue(o));
            return a;
        }
        return v;
    }

    private static Map<String, Object> toMap(JSONObject o) throws JSONException {
        Map<String, Object> m = new HashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            m.put(k, fromJsonValue(o.get(k)));
        }
        return m;
    }

    private static Object fromJsonValue(Object v) throws JSONException {
        if (v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) return toMap((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(fromJsonValue(a.get(i)));
            return out;
        }
        return v;
    }

    // ---------------------------------------------------------------- value helpers

    static String str(Object o) { return o instanceof String ? (String) o : null; }

    static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }

    static Double dbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : null; }

    static Integer integer(Object o) { return o instanceof Number ? ((Number) o).intValue() : null; }

    static Date date(Object o) {
        if (o instanceof Timestamp) return ((Timestamp) o).toDate();
        if (o instanceof Date) return (Date) o;
        if (o instanceof Number) return new Date(((Number) o).longValue());
        return null;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x instanceof String) out.add((String) x);
        return out;
    }

    private static String orDefault(String s, String def) { return s == null ? def : s; }

    public static <T> List<T> parse(QuerySnapshot snap, Parser<T> parser) {
        List<T> out = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Map<String, Object> data = d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
            if (data != null) out.add(parser.parse(d.getId(), data));
        }
        return out;
    }

    public static String errorMessage(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) e).getCode()) {
                case PERMISSION_DENIED: return "You don't have permission to do that.";
                case UNAVAILABLE: return "The server is unreachable. Check your connection and try again.";
                default: break;
            }
        }
        return e == null || e.getMessage() == null ? "Something went wrong. Please try again." : e.getMessage();
    }

    /**
     * Snapshot listener exposed as LiveData. The listener is attached only while the LiveData has
     * active observers and removed in onInactive, so screens never leak Firestore listeners.
     */
    public static final class QueryLiveData<T> extends LiveData<Resource<List<T>>> {
        private final Query query;
        private final Parser<T> parser;
        private ListenerRegistration registration;

        public QueryLiveData(Query query, Parser<T> parser) {
            this.query = query;
            this.parser = parser;
            setValue(Resource.loading(null));
        }

        @Override protected void onActive() {
            registration = query.addSnapshotListener(MetadataChanges.INCLUDE, (snap, e) -> {
                if (e != null) {
                    Resource<List<T>> prev = getValue();
                    setValue(Resource.error(errorMessage(e), prev == null ? null : prev.data));
                } else if (snap != null) {
                    setValue(Resource.success(parse(snap, parser), snap.getMetadata().isFromCache()));
                }
            });
        }

        @Override protected void onInactive() {
            if (registration != null) registration.remove();
            registration = null;
        }
    }

    /** Single-document variant of {@link QueryLiveData}. */
    public static final class DocLiveData<T> extends LiveData<Resource<T>> {
        private final DocumentReference ref;
        private final Parser<T> parser;
        private ListenerRegistration registration;

        public DocLiveData(DocumentReference ref, Parser<T> parser) {
            this.ref = ref;
            this.parser = parser;
            setValue(Resource.loading(null));
        }

        @Override protected void onActive() {
            registration = ref.addSnapshotListener((snap, e) -> {
                if (e != null) {
                    setValue(Resource.error(errorMessage(e), getValue() == null ? null : getValue().data));
                } else if (snap != null && snap.exists()) {
                    Map<String, Object> data = snap.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
                    setValue(Resource.success(parser.parse(snap.getId(), data), snap.getMetadata().isFromCache()));
                } else {
                    setValue(Resource.error("Not found", null));
                }
            });
        }

        @Override protected void onInactive() {
            if (registration != null) registration.remove();
            registration = null;
        }
    }
}
