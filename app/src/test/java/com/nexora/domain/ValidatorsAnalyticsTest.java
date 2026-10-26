package com.nexora.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Stage;
import com.nexora.domain.Models.StudentProfile;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ValidatorsAnalyticsTest {

    // ---------- InputValidators ----------

    @Test public void cgpaEdges() {
        assertNull(Validators.cgpa("0"));
        assertNull(Validators.cgpa("10"));
        assertNull(Validators.cgpa("10.00"));
        assertNull(Validators.cgpa("7.25"));
        assertNotNull(Validators.cgpa("10.01"));
        assertNotNull(Validators.cgpa("7.255"));
        assertNotNull(Validators.cgpa("-1"));
        assertNotNull(Validators.cgpa("abc"));
        assertNotNull(Validators.cgpa(""));
        assertEquals("Enter the minimum CGPA", Validators.minCgpa(" "));
        assertNull(Validators.minCgpa("7.00"));
    }

    @Test public void backlogEdges() {
        assertNull(Validators.backlogs("0"));
        assertNull(Validators.backlogs("30"));
        assertNotNull(Validators.backlogs("31"));
        assertNotNull(Validators.backlogs("-1"));
        assertNotNull(Validators.backlogs("1.5"));
    }

    @Test public void graduationYearRange() {
        assertNull(Validators.graduationYear("2026", 2026));
        assertNull(Validators.graduationYear("2031", 2026));
        assertNotNull(Validators.graduationYear("2025", 2026));
        assertNotNull(Validators.graduationYear("2032", 2026));
        assertNotNull(Validators.graduationYear("", 2026));
    }

    @Test public void rollNumberPattern() {
        assertNull(Validators.rollNumber("21CS001"));
        assertNull(Validators.rollNumber("21-CS.001"));
        assertNotNull(Validators.rollNumber("21cs001"));
        assertNotNull(Validators.rollNumber("1234"));
        assertNotNull(Validators.rollNumber("A23456789012345678901"));
        assertNull(Validators.rollNumber("ab", "^[a-z]+$"));
    }

    @Test public void urlRules() {
        assertNull(Validators.url(""));
        assertNull(Validators.url("https://drive.google.com/file/d/abc"));
        assertNull(Validators.url("http://github.com/user"));
        assertNotNull(Validators.url("ftp://x.com"));
        assertNotNull(Validators.url("drive.google.com"));
        assertNotNull(Validators.requiredUrl(""));
    }

    @Test public void accountRules() {
        assertNull(Validators.password("12345678"));
        assertNotNull(Validators.password("1234567"));
        assertNotNull(Validators.password(null));
        assertNull(Validators.email("aisha@nexora.test"));
        assertNotNull(Validators.email("aisha@"));
        assertNotNull(Validators.email(""));
        assertNull(Validators.name("Aisha"));
        assertNotNull(Validators.name(" "));
        assertNotNull(Validators.name(new String(new char[61]).replace('\0', 'a')));
    }

    @Test public void driveRules() {
        long now = Fx.NOW;
        assertNull(Validators.deadline(new Date(now + 1), now));
        assertNotNull(Validators.deadline(new Date(now), now));
        assertNotNull(Validators.deadline(null, now));
        assertNull(Validators.branches(Collections.singletonList("CSE")));
        assertNotNull(Validators.branches(new ArrayList<>()));
        assertNull(Validators.graduationYears(Collections.singletonList(2027)));
        assertNotNull(Validators.graduationYears(null));
        assertNull(Validators.packageLpa("12.5"));
        assertNotNull(Validators.packageLpa("-1"));
        assertNotNull(Validators.packageLpa("x"));
        assertNotNull(Validators.required(" ", "a title"));
    }

    @Test public void stageRules() {
        assertNull(Validators.stages(Drive.defaultStages()));
        assertNotNull(Validators.stages(Collections.singletonList(new Stage(Models.FIRST_STAGE, "SUBMISSION"))));
        assertNotNull(Validators.stages(Arrays.asList(new Stage("Test", "TEST"), new Stage(Models.LAST_STAGE, "OUTCOME"))));
        assertNotNull(Validators.stages(Arrays.asList(new Stage(Models.FIRST_STAGE, "S"), new Stage(" ", "T"))));
    }

    @Test public void activityRules() {
        Date today = new Date(Fx.NOW);
        assertNull(Validators.activity("CODING", 70, null, 60, today, Fx.NOW));
        assertNotNull(Validators.activity("CODING", null, null, 60, today, Fx.NOW));
        assertNotNull(Validators.activity("APTITUDE", 101, null, 60, today, Fx.NOW));
        assertNull(Validators.activity("INTERVIEW", null, 5, 30, today, Fx.NOW));
        assertNotNull(Validators.activity("INTERVIEW", null, 0, 30, today, Fx.NOW));
        assertNull(Validators.activity("SKILLS", null, null, 90, today, Fx.NOW));
        assertNotNull(Validators.activity("SKILLS", null, null, 0, today, Fx.NOW));
        assertNotNull(Validators.activity("SKILLS", null, null, 30, new Date(Fx.NOW + 60_000), Fx.NOW));
        assertNotNull(Validators.activity("SKILLS", null, null, 30, null, Fx.NOW));
    }

    // ---------- Analytics (manual count on a small fixture) ----------

    private static Application app(String id, String drive, String student, String status, int stage, String branch) {
        Application a = new Application();
        a.id = id; a.driveId = drive; a.studentId = student; a.status = status; a.currentStage = stage;
        a.snapBranch = branch; a.updatedAt = new Date(Fx.NOW);
        return a;
    }

    @Test public void dashboardFunnelAndBranchCounts() {
        Drive d1 = Fx.drive();
        Drive d2 = Fx.drive(); d2.id = "d2"; d2.status = "DRAFT"; d2.title = "Analyst";
        List<Application> apps = Arrays.asList(
                app("1", "d1", "s1", "SELECTED", 4, "CSE"),
                app("2", "d1", "s2", "REJECTED", 2, "ECE"),
                app("3", "d1", "s3", "IN_PROGRESS", 1, "CSE"),
                app("4", "d1", "s4", "SUBMITTED", 0, "ME"),
                app("5", "d2", "s1", "SELECTED", 4, "CSE"));
        StudentProfile v = new StudentProfile(); v.academicVerified = true;
        StudentProfile u = new StudentProfile();
        Analytics an = new Analytics();

        Analytics.Dashboard dash = an.dashboard(Arrays.asList(d1, d2), apps, Arrays.asList(v, u, u), Fx.NOW);
        assertEquals(1, dash.openDrives);
        assertEquals(5, dash.totalApplications);
        assertEquals(2, dash.pendingVerifications);
        assertEquals(2, dash.selectionsThisSeason);

        Map<String, Integer> funnel = an.funnel(d1, apps);
        assertEquals(Arrays.asList(4, 3, 2, 1, 1), new ArrayList<>(funnel.values()));

        assertEquals(Integer.valueOf(4), an.applicationsPerDrive(Arrays.asList(d1, d2), apps).get("Acme · SDE Intern"));
        Map<String, Integer> branches = an.branchSelections(apps);
        assertEquals(Integer.valueOf(2), branches.get("CSE"));
        assertNull(branches.get("ECE"));

        Map<String, Integer> readiness = new HashMap<>();
        readiness.put("s1", 80); readiness.put("s2", 40); readiness.put("s3", 60);
        int[] cmp = an.readinessComparison(apps, readiness);
        assertEquals(60, cmp[0]);
        assertEquals(80, cmp[1]);
        assertEquals(-1, an.readinessComparison(new ArrayList<>(), readiness)[0]);
    }

    // ---------------------------------------------------------------- preparation insights (officer)

    private static StudentProfile student(String uid, String name, String branch, boolean verified, String... skills) {
        StudentProfile p = Fx.student(8.0, branch, 0, 2027, verified);
        p.uid = uid; p.name = name; p.rollNumber = "R" + uid;
        p.skills = Arrays.asList(skills);
        return p;
    }

    private static Map<String, ReadinessAnalyzer.Report> reports(List<StudentProfile> ps) {
        Map<String, ReadinessAnalyzer.Report> out = new HashMap<>();
        for (StudentProfile p : ps) out.put(p.uid, new ReadinessAnalyzer().analyze(p, new ArrayList<>(), new ArrayList<>(), Fx.NOW));
        return out;
    }

    @Test public void preparationInsightsAreAggregateOnly() {
        List<StudentProfile> ps = Arrays.asList(student("u1", "Aisha Khan", "CSE", true, "java"),
                student("u2", "Rahul Verma", "CSE_AI", true), student("u3", "Kiran Das", "CSE", true, "docker"),
                student("u4", "Meera Nair", "ME", true), student("u5", "Unverified", "CSE", false));
        Drive d = Fx.drive();
        d.requiredSkills = Arrays.asList("java", "docker");
        Analytics.PrepInsights in = new Analytics().preparation(ps, Collections.singletonList(d), reports(ps), Fx.NOW);
        assertEquals(3, in.studentsTargeting); // u4 wrong branch, u5 unverified
        assertEquals(Integer.valueOf(2), in.skillGaps.get("java"));   // u2, u3
        assertEquals(Integer.valueOf(2), in.skillGaps.get("docker")); // u1, u2
        assertEquals(5, in.componentAverages.size());
        String exposed = in.skillGaps.toString() + in.componentAverages;
        for (StudentProfile p : ps) {
            assertFalse(exposed.contains(p.uid));
            assertFalse(exposed.contains(p.name));
            assertFalse(exposed.contains(p.rollNumber));
        }
    }

    @Test public void smallGroupsAndEmptyDataAreWithheld() {
        List<StudentProfile> two = Arrays.asList(student("u1", "A", "CSE", true), student("u2", "B", "CSE", true));
        Drive d = Fx.drive();
        d.requiredSkills = Collections.singletonList("docker");
        Analytics.PrepInsights small = new Analytics().preparation(two, Collections.singletonList(d), reports(two), Fx.NOW);
        assertTrue(small.componentAverages.isEmpty());
        assertTrue(small.skillGaps.isEmpty());
        Analytics.PrepInsights empty = new Analytics().preparation(new ArrayList<>(), new ArrayList<>(), new HashMap<>(), Fx.NOW);
        assertEquals(0, empty.studentsTargeting);
        assertTrue(empty.componentAverages.isEmpty() && empty.skillGaps.isEmpty());
    }

    @Test public void seasonStartsInJuly() {
        // 2027-01-15 → season began 2026-07-01; 2027-08-01 → 2027-07-01
        assertEquals(1782864000000L, Analytics.seasonStart(1800000000000L));
        assertTrue(Analytics.seasonStart(1817078400000L) > 1800000000000L);
    }

    @Test public void initialsAndApplicationId() {
        assertEquals("AK", Models.initials("Aisha  Khan"));
        assertEquals("M", Models.initials("meera"));
        assertEquals("?", Models.initials(" "));
        assertEquals("drive_uid", Models.applicationId("drive", "uid"));
    }

    // ---------- Architecture: the domain layer stays pure Java ----------

    @Test public void domainHasNoAndroidOrFirebaseImports() throws IOException {
        Path dir = Paths.get("src/main/java/com/nexora/domain");
        assertTrue("run from the app module", Files.isDirectory(dir));
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files.filter(f -> {
                try {
                    String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    return src.contains("import android.") || src.contains("import androidx.")
                            || src.contains("import com.google.firebase");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).map(Path::toString).collect(Collectors.toList());
            assertEquals(Collections.emptyList(), offenders);
        }
    }
}
