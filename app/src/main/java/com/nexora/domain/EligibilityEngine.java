package com.nexora.domain;

import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StudentProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Explainable eligibility: evaluates each academic criterion separately and explains the outcome.
 * Must agree with eligible() in firebase/firestore.rules, which is the server-enforced gate.
 */
public final class EligibilityEngine {

    public enum Status { ELIGIBLE, NOT_ELIGIBLE, NEEDS_VERIFICATION }

    public enum Criterion {
        CGPA("CGPA"), BRANCH("Branch"), BACKLOGS("Active backlogs"), GRADUATION_YEAR("Graduation year");
        public final String label;
        Criterion(String label) { this.label = label; }
    }

    public enum Outcome { PASS, FAIL, UNKNOWN }

    public static final class CriterionResult {
        public final Criterion criterion;
        public final Outcome outcome;
        public final String studentValue, requiredValue, message;

        CriterionResult(Criterion c, Outcome o, String studentValue, String requiredValue, String message) {
            this.criterion = c; this.outcome = o; this.studentValue = studentValue;
            this.requiredValue = requiredValue; this.message = message;
        }
    }

    public static final class Result {
        public final Status status;
        public final List<CriterionResult> criteria;
        public final String summary;

        Result(Status status, List<CriterionResult> criteria, String summary) {
            this.status = status; this.criteria = Collections.unmodifiableList(criteria); this.summary = summary;
        }

        /** True when no criterion failed (unknown values allowed): the match score may be shown provisionally. */
        public boolean noFailures() {
            for (CriterionResult c : criteria) if (c.outcome == Outcome.FAIL) return false;
            return true;
        }
    }

    static final double EPS = 1e-9;
    public static final String UNVERIFIED_MESSAGE =
            "Your academic record has not been verified by the placement office yet.";

    public Result evaluate(StudentProfile p, Drive d) {
        List<CriterionResult> list = new ArrayList<>();
        list.add(cgpa(p, d));
        list.add(branch(p, d));
        list.add(backlogs(p, d));
        list.add(gradYear(p, d));

        int fails = 0, unknown = 0;
        for (CriterionResult c : list) {
            if (c.outcome == Outcome.FAIL) fails++;
            else if (c.outcome == Outcome.UNKNOWN) unknown++;
        }
        if (fails > 0) {
            return new Result(Status.NOT_ELIGIBLE, list, fails == 1
                    ? "You do not meet 1 eligibility requirement."
                    : "You do not meet " + fails + " eligibility requirements.");
        }
        if (unknown > 0) {
            return new Result(Status.NEEDS_VERIFICATION, list,
                    "Complete the missing academic details in your profile so the placement office can verify them.");
        }
        if (!p.academicVerified) {
            return new Result(Status.NEEDS_VERIFICATION, list,
                    UNVERIFIED_MESSAGE + " You meet every requirement on record.");
        }
        return new Result(Status.ELIGIBLE, list, "You meet every eligibility requirement for this drive.");
    }

    private CriterionResult cgpa(StudentProfile p, Drive d) {
        String req = "≥ " + fmt2(d.minCgpa);
        if (p.cgpa == null) return unknown(Criterion.CGPA, req, "CGPA is missing from your profile.");
        String mine = fmt2(p.cgpa);
        if (p.cgpa + EPS >= d.minCgpa) {
            return new CriterionResult(Criterion.CGPA, Outcome.PASS, mine, req,
                    "Your CGPA is " + mine + "; this drive requires at least " + fmt2(d.minCgpa) + ".");
        }
        return new CriterionResult(Criterion.CGPA, Outcome.FAIL, mine, req,
                "Your CGPA is " + mine + "; this drive requires at least " + fmt2(d.minCgpa)
                        + " (short by " + fmt2(d.minCgpa - p.cgpa) + ").");
    }

    private CriterionResult branch(StudentProfile p, Drive d) {
        String req = d.eligibleBranches.isEmpty() ? "none" : String.join(", ", d.eligibleBranches);
        if (isBlank(p.branch)) return unknown(Criterion.BRANCH, req, "Branch is missing from your profile.");
        if (d.eligibleBranches.isEmpty()) {
            return new CriterionResult(Criterion.BRANCH, Outcome.FAIL, p.branch, req,
                    "This drive has no eligible branches configured.");
        }
        if (d.eligibleBranches.contains(p.branch)) {
            return new CriterionResult(Criterion.BRANCH, Outcome.PASS, p.branch, req,
                    "Your branch " + p.branch + " is eligible.");
        }
        return new CriterionResult(Criterion.BRANCH, Outcome.FAIL, p.branch, req,
                "Your branch " + p.branch + " is not eligible; this drive is open to " + req + ".");
    }

    private CriterionResult backlogs(StudentProfile p, Drive d) {
        String req = "≤ " + d.maxActiveBacklogs;
        if (p.activeBacklogs == null) {
            return unknown(Criterion.BACKLOGS, req, "Active backlog count is missing from your profile.");
        }
        String mine = String.valueOf(p.activeBacklogs);
        if (p.activeBacklogs <= d.maxActiveBacklogs) {
            return new CriterionResult(Criterion.BACKLOGS, Outcome.PASS, mine, req,
                    "You have " + plural(p.activeBacklogs, "active backlog") + "; up to "
                            + d.maxActiveBacklogs + " allowed.");
        }
        return new CriterionResult(Criterion.BACKLOGS, Outcome.FAIL, mine, req,
                "You have " + plural(p.activeBacklogs, "active backlog") + "; this drive allows at most "
                        + d.maxActiveBacklogs + ".");
    }

    private CriterionResult gradYear(StudentProfile p, Drive d) {
        List<String> years = new ArrayList<>();
        for (Integer y : d.graduationYears) years.add(String.valueOf(y));
        String req = years.isEmpty() ? "none" : String.join(", ", years);
        if (p.graduationYear == null) {
            return unknown(Criterion.GRADUATION_YEAR, req, "Graduation year is missing from your profile.");
        }
        String mine = String.valueOf(p.graduationYear);
        if (d.graduationYears.contains(p.graduationYear)) {
            return new CriterionResult(Criterion.GRADUATION_YEAR, Outcome.PASS, mine, req,
                    "Your graduation year " + mine + " is eligible.");
        }
        return new CriterionResult(Criterion.GRADUATION_YEAR, Outcome.FAIL, mine, req,
                "Your graduation year is " + mine + "; this drive is for the batch of " + req + ".");
    }

    private static CriterionResult unknown(Criterion c, String req, String msg) {
        return new CriterionResult(c, Outcome.UNKNOWN, "—", req, msg);
    }

    static String fmt2(double v) { return String.format(Locale.US, "%.2f", v); }

    private static String plural(int n, String word) { return n + " " + word + (n == 1 ? "" : "s"); }

    static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
