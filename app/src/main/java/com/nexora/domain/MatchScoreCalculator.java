package com.nexora.domain;

import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StudentProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explainable match score: Match = wS·S + wP·P + wR·R.
 * S = skill coverage, P = role-preference fit, R = readiness for the drive's preparation focus.
 * The score ranks opportunities; it does not predict selection.
 */
public final class MatchScoreCalculator {

    public enum Band { STRONG, GOOD, FAIR }

    public static final class Weights {
        public final double skills, preference, readiness;

        public Weights(double skills, double preference, double readiness) {
            if (Math.abs(skills + preference + readiness - 1.0) > 1e-6) {
                throw new IllegalArgumentException("Match weights must sum to 1.0");
            }
            this.skills = skills; this.preference = preference; this.readiness = readiness;
        }

        public static final Weights DEFAULT = new Weights(0.60, 0.25, 0.15);
    }

    public static final class Result {
        public int score, s, p, r;
        public boolean provisional;
        public Band band;
        public final List<String> matchedSkills = new ArrayList<>();
        public final List<String> missingSkills = new ArrayList<>();
        public final List<String> reasons = new ArrayList<>();
    }

    public static final String DISCLAIMER =
            "The match score ranks opportunities for you; it does not predict selection.";

    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("js", "javascript");
        ALIASES.put("ts", "typescript");
        ALIASES.put("cpp", "c++");
        ALIASES.put("c plus plus", "c++");
        ALIASES.put("ml", "machine learning");
        ALIASES.put("ai", "artificial intelligence");
        ALIASES.put("py", "python");
        ALIASES.put("golang", "go");
        ALIASES.put("nodejs", "node.js");
        ALIASES.put("node", "node.js");
        ALIASES.put("reactjs", "react");
        ALIASES.put("k8s", "kubernetes");
        ALIASES.put("dsa", "data structures");
    }

    private final Weights weights;

    public MatchScoreCalculator() { this(Weights.DEFAULT); }
    public MatchScoreCalculator(Weights weights) { this.weights = weights; }

    /** Lower-cases, trims, collapses whitespace and resolves common aliases. */
    public static String normalizeSkill(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String alias = ALIASES.get(s);
        return alias != null ? alias : s;
    }

    public static List<String> normalizeSkills(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) for (String s : raw) {
            String n = normalizeSkill(s);
            if (!n.isEmpty()) out.add(n);
        }
        return new ArrayList<>(out);
    }

    /** A match score is only shown for eligible drives, or provisionally when nothing failed. */
    public static boolean applies(EligibilityEngine.Result e) {
        return e.status == EligibilityEngine.Status.ELIGIBLE
                || (e.status == EligibilityEngine.Status.NEEDS_VERIFICATION && e.noFailures());
    }

    /**
     * @param readiness component percentages from {@link ReadinessAnalyzer}
     * @param overallReadiness overall readiness, used when the drive has no preparation focus
     */
    public Result calculate(StudentProfile p, Drive d, Map<Component, Integer> readiness, int overallReadiness) {
        Result res = new Result();

        // S — skills
        List<String> required = normalizeSkills(d.requiredSkills);
        Set<String> mine = new LinkedHashSet<>(normalizeSkills(p.skills));
        for (String skill : required) (mine.contains(skill) ? res.matchedSkills : res.missingSkills).add(skill);
        double s = required.isEmpty() ? 100.0 : 100.0 * res.matchedSkills.size() / required.size();
        if (required.isEmpty()) {
            res.reasons.add("This drive lists no required skills, so skill coverage is full.");
        } else if (res.missingSkills.isEmpty()) {
            res.reasons.add("You have all " + required.size() + " required skills.");
        } else {
            res.reasons.add("You have " + res.matchedSkills.size() + " of " + required.size()
                    + " required skills (missing: " + String.join(", ", res.missingSkills) + ").");
        }

        // P — role preference
        double pScore;
        String roleLabel = roleLabel(d.roleCategory);
        if (p.preferredRoles == null || p.preferredRoles.isEmpty()) {
            pScore = 50;
            res.reasons.add("Add preferred roles to your profile to personalise this score.");
        } else if (p.preferredRoles.contains(d.roleCategory)) {
            pScore = 100;
            res.reasons.add("Matches your preferred role: " + roleLabel + ".");
        } else {
            pScore = 0;
            res.reasons.add(roleLabel + " is not among your preferred roles.");
        }

        // R — readiness on the drive's preparation focus
        double r;
        List<Component> focus = new ArrayList<>();
        if (d.prepFocus != null) for (String f : d.prepFocus) {
            try {
                Component c = Component.valueOf(f);
                if (c != Component.RESUME && !focus.contains(c)) focus.add(c);
            } catch (IllegalArgumentException ignored) { }
        }
        if (focus.isEmpty()) {
            r = overallReadiness;
        } else {
            double sum = 0;
            for (Component c : focus) {
                int pct = readiness.containsKey(c) ? readiness.get(c) : 0;
                sum += pct;
                if (pct < 60) {
                    res.reasons.add(c.label + " practice is at " + pct + "% — log more practice to improve.");
                }
            }
            r = sum / focus.size();
        }

        res.s = (int) Math.round(s);
        res.p = (int) Math.round(pScore);
        res.r = (int) Math.round(r);
        res.score = (int) Math.round(weights.skills * s + weights.preference * pScore + weights.readiness * r);
        res.band = band(res.score);
        return res;
    }

    public static Band band(int score) {
        if (score >= 75) return Band.STRONG;
        if (score >= 50) return Band.GOOD;
        return Band.FAIR;
    }

    static String roleLabel(String category) {
        try {
            return Models.RoleCategory.valueOf(category).label;
        } catch (RuntimeException e) {
            return String.valueOf(category);
        }
    }
}
