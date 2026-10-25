package com.nexora.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StageRecord;
import com.nexora.domain.Models.StudentProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ReadinessWorkflowTest {
    private static final long DAY = ReadinessAnalyzer.DAY;
    private final ReadinessAnalyzer analyzer = new ReadinessAnalyzer();
    private final ApplicationWorkflow wf = new ApplicationWorkflow();
    private final EligibilityEngine engine = new EligibilityEngine();

    private static Activity act(String type, Integer score, Integer rating, int minutes, long daysAgo) {
        Activity a = new Activity();
        a.id = type + daysAgo + Math.random();
        a.type = type; a.scorePercent = score; a.rating = rating; a.durationMin = minutes;
        a.date = new Date(Fx.NOW - daysAgo * DAY);
        return a;
    }

    // ---------- ReadinessAnalyzer ----------

    @Test public void noActivityLeavesOnlyResumeComponent() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.resumeUrl = "https://drive.google.com/x";
        p.skills = Arrays.asList("java", "sql", "git");
        p.certifications = Collections.singletonList("AWS");
        p.summary = "Hi";
        p.githubUrl = "https://github.com/a";
        ReadinessAnalyzer.Report r = analyzer.analyze(p, new ArrayList<>(), new ArrayList<>(), Fx.NOW);
        assertEquals(100, (int) r.percents.get(Component.RESUME));
        assertEquals(0, (int) r.percents.get(Component.CODING));
        assertEquals(20, r.overall);
    }

    @Test public void resumeCompletenessIsFractionOfFiveChecks() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.linkedinUrl = "https://linkedin.com/in/a";
        p.skills = Arrays.asList("java", "sql");
        assertEquals(20, ReadinessAnalyzer.resumePercent(p));
        p.skills = Arrays.asList("java", "sql", "c");
        assertEquals(40, ReadinessAnalyzer.resumePercent(p));
    }

    @Test public void thirtyDayWindowBoundary() {
        StudentProfile p = new StudentProfile();
        List<Activity> in = Collections.singletonList(act("SKILLS", null, null, 600, 30));
        List<Activity> out = Collections.singletonList(act("SKILLS", null, null, 600, 31));
        assertEquals(100, (int) analyzer.analyze(p, in, new ArrayList<>(), Fx.NOW).percents.get(Component.SKILLS));
        assertEquals(0, (int) analyzer.analyze(p, out, new ArrayList<>(), Fx.NOW).percents.get(Component.SKILLS));
    }

    @Test public void futureActivitiesIgnored() {
        List<Activity> future = Collections.singletonList(act("SKILLS", null, null, 600, -2));
        assertEquals(0, (int) analyzer.analyze(new StudentProfile(), future, new ArrayList<>(), Fx.NOW).percents.get(Component.SKILLS));
    }

    @Test public void countCapsScaleScoredComponents() {
        StudentProfile p = new StudentProfile();
        List<Activity> two = Arrays.asList(act("CODING", 80, null, 60, 1), act("CODING", 60, null, 60, 2));
        assertEquals(35, (int) analyzer.analyze(p, two, new ArrayList<>(), Fx.NOW).percents.get(Component.CODING)); // 70 × 0.5
        List<Activity> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(act("APTITUDE", 90, null, 30, i));
        assertEquals(90, (int) analyzer.analyze(p, six, new ArrayList<>(), Fx.NOW).percents.get(Component.APTITUDE));
    }

    @Test public void interviewAndSkillFormulas() {
        StudentProfile p = new StudentProfile();
        List<Activity> one = Collections.singletonList(act("INTERVIEW", null, 4, 45, 3));
        assertEquals(40, (int) analyzer.analyze(p, one, new ArrayList<>(), Fx.NOW).percents.get(Component.INTERVIEW)); // 80 × 0.5
        List<Activity> both = Arrays.asList(act("INTERVIEW", null, 4, 45, 3), act("INTERVIEW", null, 5, 45, 4));
        assertEquals(90, (int) analyzer.analyze(p, both, new ArrayList<>(), Fx.NOW).percents.get(Component.INTERVIEW));
        List<Activity> skills = Arrays.asList(act("SKILLS", null, null, 200, 1), act("SKILLS", null, null, 100, 2));
        assertEquals(50, (int) analyzer.analyze(p, skills, new ArrayList<>(), Fx.NOW).percents.get(Component.SKILLS));
    }

    @Test public void overallIsWeightedSum() {
        List<Activity> acts = new ArrayList<>();
        for (int i = 0; i < 4; i++) { acts.add(act("APTITUDE", 100, null, 30, i)); acts.add(act("CODING", 100, null, 30, i)); }
        // aptitude 100×25 + coding 100×25 = 50
        assertEquals(50, analyzer.analyze(new StudentProfile(), acts, new ArrayList<>(), Fx.NOW).overall);
    }

    @Test public void weeklyBucketing() {
        List<Activity> acts = Arrays.asList(act("SKILLS", null, null, 30, 0), act("SKILLS", null, null, 30, 6),
                act("SKILLS", null, null, 30, 7), act("SKILLS", null, null, 30, 41), act("SKILLS", null, null, 30, 42));
        ReadinessAnalyzer.Report r = analyzer.analyze(new StudentProfile(), acts, new ArrayList<>(), Fx.NOW);
        assertEquals(6, r.last6Weeks.size());
        assertEquals(2, r.last6Weeks.get(5).activities); // most recent week: day 0 and day 6
        assertEquals(60, r.last6Weeks.get(5).minutes);
        assertEquals(1, r.last6Weeks.get(4).activities); // day 7
        assertEquals(1, r.last6Weeks.get(0).activities); // day 41; day 42 falls outside
    }

    @Test public void suggestionsLinkClosingDrivesToWeakFocus() {
        Drive a = Fx.drive(); a.id = "a"; a.prepFocus = Collections.singletonList("CODING");
        Drive b = Fx.drive(); b.id = "b"; b.prepFocus = Arrays.asList("CODING", "APTITUDE");
        Drive c = Fx.drive(); c.id = "c"; c.prepFocus = Collections.singletonList("CODING");
        Drive later = Fx.drive(); later.id = "later"; later.prepFocus = Collections.singletonList("SKILLS");
        later.deadline = new Date(Fx.NOW + 20 * DAY);
        List<Activity> acts = Arrays.asList(act("CODING", 70, null, 30, 1), act("CODING", 70, null, 30, 2));
        ReadinessAnalyzer.Report r = analyzer.analyze(new StudentProfile(), acts, Arrays.asList(a, b, c, later), Fx.NOW);
        assertEquals("3 eligible drives close in the next 7 days and focus on CODING; your coding readiness is 35%.",
                r.suggestions.get(0));
        assertEquals(3, r.suggestions.size()); // drive link + two weakest components
        assertTrue(r.relevantDriveIds.containsAll(Arrays.asList("a", "b", "c")));
    }

    @Test public void suggestionActionsCoverEveryComponent() {
        for (Component c : Component.values()) assertTrue(ReadinessAnalyzer.action(c, 10).contains("10%"));
    }

    // ---------- ApplicationWorkflowManager ----------

    private Application submitted() {
        StudentProfile p = Fx.student(8.4, "CSE_AI", 0, 2027, true);
        return wf.newApplication(p, Fx.drive(), 87);
    }

    @Test public void newApplicationHasDeterministicIdAndSnapshot() {
        Application a = submitted();
        assertEquals("d1_s1", a.id);
        assertEquals(AppStatus.SUBMITTED.name(), a.status);
        assertEquals(0, a.currentStage);
        assertEquals(8.4, a.snapCgpa, 1e-9);
        assertEquals("CSE_AI", a.snapBranch);
        assertEquals(100, wf.newApplication(Fx.student(8, "CSE", 0, 2027, true), Fx.drive(), 250).matchScore);
    }

    @Test public void canApplyChecks() {
        Drive d = Fx.drive();
        EligibilityEngine.Result ok = engine.evaluate(Fx.student(8, "CSE", 0, 2027, true), d);
        assertTrue(wf.canApply(ok, d, null, Fx.NOW).allowed);
        assertNull(wf.canApply(ok, d, null, Fx.NOW).reason);
        assertFalse(wf.canApply(ok, d, submitted(), Fx.NOW).allowed);
        assertFalse(wf.canApply(ok, d, null, d.deadline.getTime()).allowed); // deadline is exclusive
        assertFalse(wf.canApply(engine.evaluate(Fx.student(6.99, "CSE", 0, 2027, true), d), d, null, Fx.NOW).allowed);
        assertFalse(wf.canApply(engine.evaluate(Fx.student(8, "CSE", 0, 2027, false), d), d, null, Fx.NOW).allowed);
        d.status = "CLOSED";
        assertTrue(wf.canApply(ok, d, null, Fx.NOW).reason.startsWith("Applications closed"));
        d.status = "OPEN"; d.deadline = null;
        assertFalse(wf.canApply(ok, d, null, Fx.NOW).allowed);
    }

    @Test public void advanceThroughAllStagesEndsSelected() {
        Drive d = Fx.drive(); // 5 stages
        Application a = submitted();
        a = wf.advance(a, d, "Cleared OA", new Date(Fx.NOW));
        assertEquals(AppStatus.IN_PROGRESS.name(), a.status);
        assertEquals(1, a.currentStage);
        assertEquals("Cleared OA", a.stageHistory.get(0).note);
        a = wf.advance(a, d, "", new Date(Fx.NOW));
        a = wf.advance(a, d, null, new Date(Fx.NOW));
        assertEquals(AppStatus.IN_PROGRESS.name(), a.status);
        a = wf.advance(a, d, null, new Date(Fx.NOW));
        assertEquals(AppStatus.SELECTED.name(), a.status);
        assertEquals(4, a.currentStage);
        assertEquals(4, a.stageHistory.size());
        assertNull(a.stageHistory.get(1).note);
        assertTrue(ApplicationWorkflow.updateMessage(a, d).startsWith("Congratulations"));
    }

    @Test public void rejectRecordsFailureAtCurrentStage() {
        Drive d = Fx.drive();
        Application a = wf.advance(submitted(), d, null, new Date(Fx.NOW));
        Application r = wf.reject(a, "Did not clear", new Date(Fx.NOW));
        assertEquals(AppStatus.REJECTED.name(), r.status);
        assertEquals(1, r.currentStage);
        StageRecord last = r.stageHistory.get(r.stageHistory.size() - 1);
        assertEquals(StageRecord.FAILED, last.result);
        assertEquals(1, last.stageIndex);
        assertEquals(AppStatus.IN_PROGRESS.name(), a.status); // original untouched
        assertTrue(ApplicationWorkflow.updateMessage(r, d).contains("not taken forward"));
        assertTrue(ApplicationWorkflow.updateMessage(a, d).contains("Online Assessment"));
    }

    @Test public void finalStatesAreImmutable() {
        Drive d = Fx.drive();
        Application rejected = wf.reject(submitted(), null, new Date(Fx.NOW));
        try { wf.advance(rejected, d, null, new Date(Fx.NOW)); fail(); } catch (IllegalStateException expected) { }
        try { wf.reject(rejected, null, new Date(Fx.NOW)); fail(); } catch (IllegalStateException expected) { }
        assertFalse(ApplicationWorkflow.isAllowedTransition(AppStatus.SELECTED, AppStatus.REJECTED));
        assertFalse(ApplicationWorkflow.isAllowedTransition(AppStatus.REJECTED, AppStatus.IN_PROGRESS));
        assertTrue(ApplicationWorkflow.isAllowedTransition(AppStatus.SUBMITTED, AppStatus.IN_PROGRESS));
        assertTrue(ApplicationWorkflow.isAllowedTransition(AppStatus.IN_PROGRESS, AppStatus.REJECTED));
        assertFalse(ApplicationWorkflow.isAllowedTransition(AppStatus.IN_PROGRESS, AppStatus.SUBMITTED));
    }

    @Test public void cannotAdvancePastFinalStage() {
        Drive d = Fx.drive();
        d.stages = d.stages.subList(0, 1);
        try { wf.advance(submitted(), d, null, new Date(Fx.NOW)); fail(); } catch (IllegalStateException expected) { }
    }
}
