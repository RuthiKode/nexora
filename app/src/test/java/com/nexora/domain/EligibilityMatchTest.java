package com.nexora.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexora.domain.EligibilityEngine.Criterion;
import com.nexora.domain.EligibilityEngine.Outcome;
import com.nexora.domain.EligibilityEngine.Status;
import com.nexora.domain.MatchScoreCalculator.Band;
import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StudentProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

/** Shared fixtures. The boundary values mirror firebase/tests/rules.test.js (parity requirement). */
final class Fx {
    static final long NOW = 1_800_000_000_000L;

    static StudentProfile student(double cgpa, String branch, int backlogs, int year, boolean verified) {
        StudentProfile p = new StudentProfile();
        p.uid = "s1"; p.name = "Aisha Khan"; p.rollNumber = "21CS001";
        p.cgpa = cgpa; p.branch = branch; p.activeBacklogs = backlogs; p.graduationYear = year;
        p.academicVerified = verified;
        return p;
    }

    /** min CGPA 7.00, CSE/CSE_AI, 0 backlogs, batch 2027 — the seed "boundary" drive. */
    static Drive drive() {
        Drive d = new Drive();
        d.id = "d1"; d.title = "SDE Intern"; d.companyName = "Acme";
        d.minCgpa = 7.0; d.maxActiveBacklogs = 0;
        d.eligibleBranches = new ArrayList<>(Arrays.asList("CSE", "CSE_AI"));
        d.graduationYears = new ArrayList<>(Collections.singletonList(2027));
        d.roleCategory = "SDE";
        d.status = "OPEN";
        d.deadline = new Date(NOW + 2 * ReadinessAnalyzer.DAY);
        d.stages = Drive.defaultStages();
        return d;
    }
}

public class EligibilityMatchTest {
    private final EligibilityEngine engine = new EligibilityEngine();
    private final MatchScoreCalculator calc = new MatchScoreCalculator();

    private EligibilityEngine.CriterionResult crit(EligibilityEngine.Result r, Criterion c) {
        for (EligibilityEngine.CriterionResult x : r.criteria) if (x.criterion == c) return x;
        throw new AssertionError("missing " + c);
    }

    // ---------- EligibilityEngine ----------

    @Test public void cgpaEqualToMinimumPasses() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(7.00, "CSE", 0, 2027, true), Fx.drive());
        assertEquals(Outcome.PASS, crit(r, Criterion.CGPA).outcome);
        assertEquals(Status.ELIGIBLE, r.status);
    }

    @Test public void cgpaJustBelowMinimumFailsWithGap() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(6.99, "CSE", 0, 2027, true), Fx.drive());
        assertEquals(Outcome.FAIL, crit(r, Criterion.CGPA).outcome);
        assertEquals(Status.NOT_ELIGIBLE, r.status);
        assertTrue(crit(r, Criterion.CGPA).message.contains("short by 0.01"));
    }

    @Test public void cgpaMessageMatchesSpec() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(6.85, "CSE", 0, 2027, true), Fx.drive());
        assertEquals("Your CGPA is 6.85; this drive requires at least 7.00 (short by 0.15).",
                crit(r, Criterion.CGPA).message);
    }

    @Test public void perfectCgpaAgainstPerfectMinimumPasses() {
        Drive d = Fx.drive();
        d.minCgpa = 10.0;
        assertEquals(Outcome.PASS, crit(engine.evaluate(Fx.student(10.0, "CSE", 0, 2027, true), d), Criterion.CGPA).outcome);
    }

    @Test public void branchNotInListFails() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(8.9, "ME", 0, 2027, true), Fx.drive());
        assertEquals(Outcome.FAIL, crit(r, Criterion.BRANCH).outcome);
        assertEquals(Status.NOT_ELIGIBLE, r.status);
        assertTrue(crit(r, Criterion.BRANCH).message.contains("CSE, CSE_AI"));
    }

    @Test public void emptyBranchListFailsWithMessage() {
        Drive d = Fx.drive();
        d.eligibleBranches.clear();
        EligibilityEngine.CriterionResult c = crit(engine.evaluate(Fx.student(8, "CSE", 0, 2027, true), d), Criterion.BRANCH);
        assertEquals(Outcome.FAIL, c.outcome);
        assertEquals("This drive has no eligible branches configured.", c.message);
    }

    @Test public void backlogsAtMaximumPassAndAboveFail() {
        Drive d = Fx.drive();
        d.maxActiveBacklogs = 2;
        assertEquals(Outcome.PASS, crit(engine.evaluate(Fx.student(8, "CSE", 2, 2027, true), d), Criterion.BACKLOGS).outcome);
        assertEquals(Outcome.FAIL, crit(engine.evaluate(Fx.student(8, "CSE", 3, 2027, true), d), Criterion.BACKLOGS).outcome);
    }

    @Test public void zeroBacklogsAllowedWhenMaximumIsZero() {
        assertEquals(Outcome.PASS, crit(engine.evaluate(Fx.student(8, "CSE", 0, 2027, true), Fx.drive()), Criterion.BACKLOGS).outcome);
        assertEquals(Outcome.FAIL, crit(engine.evaluate(Fx.student(8, "CSE", 1, 2027, true), Fx.drive()), Criterion.BACKLOGS).outcome);
    }

    @Test public void graduationYearNotInListFails() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(8, "CSE", 0, 2028, true), Fx.drive());
        assertEquals(Outcome.FAIL, crit(r, Criterion.GRADUATION_YEAR).outcome);
        assertEquals(Status.NOT_ELIGIBLE, r.status);
    }

    @Test public void missingCgpaNeedsVerification() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.cgpa = null;
        EligibilityEngine.Result r = engine.evaluate(p, Fx.drive());
        assertEquals(Outcome.UNKNOWN, crit(r, Criterion.CGPA).outcome);
        assertEquals(Status.NEEDS_VERIFICATION, r.status);
    }

    @Test public void failDominatesUnknown() {
        StudentProfile p = Fx.student(8, "ME", 0, 2027, true);
        p.cgpa = null;
        assertEquals(Status.NOT_ELIGIBLE, engine.evaluate(p, Fx.drive()).status);
    }

    @Test public void missingValuesProduceSpecificMessages() {
        StudentProfile p = new StudentProfile();
        EligibilityEngine.Result r = engine.evaluate(p, Fx.drive());
        assertEquals(Status.NEEDS_VERIFICATION, r.status);
        assertEquals("Graduation year is missing from your profile.", crit(r, Criterion.GRADUATION_YEAR).message);
        for (EligibilityEngine.CriterionResult c : r.criteria) assertEquals(Outcome.UNKNOWN, c.outcome);
    }

    @Test public void allPassButUnverifiedNeedsVerification() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(8.4, "CSE_AI", 0, 2027, false), Fx.drive());
        assertEquals(Status.NEEDS_VERIFICATION, r.status);
        assertTrue(r.summary.startsWith(EligibilityEngine.UNVERIFIED_MESSAGE));
        assertTrue(r.noFailures());
    }

    @Test public void criteriaAreInDisplayOrder() {
        EligibilityEngine.Result r = engine.evaluate(Fx.student(8, "CSE", 0, 2027, true), Fx.drive());
        assertEquals(Arrays.asList(Criterion.CGPA, Criterion.BRANCH, Criterion.BACKLOGS, Criterion.GRADUATION_YEAR),
                Arrays.asList(r.criteria.get(0).criterion, r.criteria.get(1).criterion,
                        r.criteria.get(2).criterion, r.criteria.get(3).criterion));
    }

    // ---------- MatchScoreCalculator ----------

    private Map<Component, Integer> readiness(int apt, int coding, int interview, int skills) {
        Map<Component, Integer> m = new EnumMap<>(Component.class);
        m.put(Component.RESUME, 0); m.put(Component.APTITUDE, apt); m.put(Component.CODING, coding);
        m.put(Component.INTERVIEW, interview); m.put(Component.SKILLS, skills);
        return m;
    }

    @Test public void emptyRequiredSkillsGiveFullSkillScore() {
        MatchScoreCalculator.Result r = calc.calculate(Fx.student(8, "CSE", 0, 2027, true), Fx.drive(), readiness(0, 0, 0, 0), 0);
        assertEquals(100, r.s);
    }

    @Test public void skillsAreNormalisedForCaseWhitespaceAndAliases() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.skills = Arrays.asList("  JS ", "CPP", "ml", "Python");
        Drive d = Fx.drive();
        d.requiredSkills = Arrays.asList("JavaScript", "c++", "Machine   Learning", "Docker");
        MatchScoreCalculator.Result r = calc.calculate(p, d, readiness(0, 0, 0, 0), 0);
        assertEquals(Arrays.asList("javascript", "c++", "machine learning"), r.matchedSkills);
        assertEquals(Collections.singletonList("docker"), r.missingSkills);
        assertEquals(75, r.s);
        assertTrue(r.reasons.get(0).contains("3 of 4 required skills (missing: docker)"));
    }

    @Test public void preferenceScores() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        Drive d = Fx.drive();
        p.preferredRoles = new ArrayList<>();
        assertEquals(50, calc.calculate(p, d, readiness(0, 0, 0, 0), 0).p);
        p.preferredRoles = Collections.singletonList("SDE");
        assertEquals(100, calc.calculate(p, d, readiness(0, 0, 0, 0), 0).p);
        p.preferredRoles = Collections.singletonList("DATA");
        assertEquals(0, calc.calculate(p, d, readiness(0, 0, 0, 0), 0).p);
    }

    @Test public void readinessUsesDriveFocusOrOverall() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        Drive d = Fx.drive();
        assertEquals(42, calc.calculate(p, d, readiness(10, 20, 30, 40), 42).r);
        d.prepFocus = Arrays.asList("CODING", "INTERVIEW");
        MatchScoreCalculator.Result r = calc.calculate(p, d, readiness(10, 20, 30, 40), 42);
        assertEquals(25, r.r);
        assertTrue(r.reasons.contains("Coding practice is at 20% — log more practice to improve."));
    }

    @Test public void weightedScoreAndRounding() {
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.skills = Arrays.asList("java", "sql");
        p.preferredRoles = Collections.singletonList("SDE");
        Drive d = Fx.drive();
        d.requiredSkills = Arrays.asList("java", "sql", "docker"); // S = 66.67
        d.prepFocus = Collections.singletonList("CODING");
        // 0.6*66.667 + 0.25*100 + 0.15*35 = 40 + 25 + 5.25 = 70.25 → 70
        MatchScoreCalculator.Result r = calc.calculate(p, d, readiness(0, 35, 0, 0), 0);
        assertEquals(67, r.s);
        assertEquals(70, r.score);
        assertEquals(Band.GOOD, r.band);
    }

    @Test public void defaultWeightsSumToOneAndInvalidWeightsRejected() {
        MatchScoreCalculator.Weights w = MatchScoreCalculator.Weights.DEFAULT;
        assertEquals(1.0, w.skills + w.preference + w.readiness, 1e-9);
        assertEquals(0.60, w.skills, 1e-9);
        try {
            new MatchScoreCalculator.Weights(0.5, 0.5, 0.5);
            throw new AssertionError("expected rejection");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void bandThresholds() {
        assertEquals(Band.STRONG, MatchScoreCalculator.band(75));
        assertEquals(Band.GOOD, MatchScoreCalculator.band(74));
        assertEquals(Band.GOOD, MatchScoreCalculator.band(50));
        assertEquals(Band.FAIR, MatchScoreCalculator.band(49));
    }

    @Test public void appliesOnlyToEligibleOrProvisional() {
        assertTrue(MatchScoreCalculator.applies(engine.evaluate(Fx.student(8, "CSE", 0, 2027, true), Fx.drive())));
        assertTrue(MatchScoreCalculator.applies(engine.evaluate(Fx.student(8, "CSE", 0, 2027, false), Fx.drive())));
        assertFalse(MatchScoreCalculator.applies(engine.evaluate(Fx.student(6.99, "CSE", 0, 2027, true), Fx.drive())));
    }

    @Test public void customWeightsAreApplied() {
        MatchScoreCalculator onlySkills = new MatchScoreCalculator(new MatchScoreCalculator.Weights(1, 0, 0));
        assertEquals(100, onlySkills.calculate(Fx.student(8, "CSE", 0, 2027, true), Fx.drive(), readiness(0, 0, 0, 0), 0).score);
    }

    @Test public void unknownRoleCategoryAndFocusAreTolerated() {
        Drive d = Fx.drive();
        d.roleCategory = "WEIRD";
        d.prepFocus = Arrays.asList("NOPE", "RESUME");
        StudentProfile p = Fx.student(8, "CSE", 0, 2027, true);
        p.preferredRoles = Collections.singletonList("SDE");
        MatchScoreCalculator.Result r = calc.calculate(p, d, readiness(0, 0, 0, 0), 30);
        assertEquals(0, r.p);
        assertEquals(30, r.r);
        assertEquals("", MatchScoreCalculator.normalizeSkill(null));
    }
}
