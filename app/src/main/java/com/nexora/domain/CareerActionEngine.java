package com.nexora.domain;

import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.Stage;
import com.nexora.domain.Models.StudentProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Career Action Engine: a deterministic, rule-based "next steps" plan built only from results the
 * other engines already produced (eligibility, match score, apply decision, readiness report,
 * application stages). It never re-computes eligibility or scores and never predicts outcomes.
 *
 * Ranking (higher first), documented so it stays transparent:
 *   VERIFY    1000                         blocker: eligibility cannot be confirmed
 *   STAGE      900 − 10 × days until stage scheduled round of an active application (≤ 14 days)
 *   APPLY      800 − hours left / 6         eligible, not applied, closes within 3 days (urgent)
 *   APPLY      500 + match − days left      other eligible, not-applied, open drives (≤ 30 days counted)
 *   SKILL      300 + 10 × drives needing it missing required skill of target drives
 *   READINESS  200 + (60 − component %)     weakest focus component below 60 %
 *   PROFILE    150                          resume completeness below 100 %
 * At most {@link #MAX_ACTIONS} actions, at most 2 apply and 2 skill actions, no duplicates.
 */
public final class CareerActionEngine {

    public static final int MAX_ACTIONS = 5;
    static final long DAY = ReadinessAnalyzer.DAY;
    static final int STAGE_WINDOW_DAYS = 14, URGENT_DAYS = 3, WEAK_PERCENT = 60;

    public enum Kind { VERIFY, STAGE, APPLY, SKILL, READINESS, PROFILE }

    /** A drive with the results the other engines produced for this student. */
    public static class Opportunity {
        public Drive drive;
        public EligibilityEngine.Result eligibility;
        public MatchScoreCalculator.Result match; // null when not applicable
        public ApplicationWorkflow.Decision decision;
        public Application application;
    }

    public static final class Action {
        public final Kind kind;
        public final int priority;
        public final String title, why, next;
        /** Optional context: the drive / application / component the action is about. */
        public String driveId, applicationId, skill;
        public Component component;

        Action(Kind kind, int priority, String title, String why, String next) {
            this.kind = kind; this.priority = priority; this.title = title; this.why = why; this.next = next;
        }
    }

    public List<Action> plan(StudentProfile p, List<? extends Opportunity> opps,
                             ReadinessAnalyzer.Report readiness, long now) {
        List<Action> out = new ArrayList<>();
        verification(p, out);
        stages(opps, now, out);
        applications(opps, now, out);
        List<Opportunity> targets = targets(opps, now);
        skills(targets, out);
        readiness(readiness, targets, out);

        out.sort(Comparator.comparingInt((Action a) -> -a.priority).thenComparing(a -> a.title));
        List<Action> ranked = new ArrayList<>();
        int apply = 0, skill = 0;
        for (Action a : out) {
            if (ranked.size() == MAX_ACTIONS) break;
            if (a.kind == Kind.APPLY && apply++ >= 2) continue;
            if (a.kind == Kind.SKILL && skill++ >= 2) continue;
            ranked.add(a);
        }
        return ranked;
    }

    // ---------------------------------------------------------------- D. academic verification

    private static void verification(StudentProfile p, List<Action> out) {
        if (p.academicVerified) return;
        boolean incomplete = p.rollNumber == null || p.branch == null || p.cgpa == null
                || p.activeBacklogs == null || p.graduationYear == null;
        out.add(new Action(Kind.VERIFY, 1000,
                incomplete ? "Complete your academic record" : "Academic record awaiting verification",
                "Eligibility can't be confirmed until the placement office verifies your record, so you can't apply yet.",
                incomplete ? "Add your roll number, branch, CGPA, backlogs and graduation year."
                        : "Check your details are correct; the placement office will verify them."));
    }

    // ---------------------------------------------------------------- E. recruitment journey

    private static void stages(List<? extends Opportunity> opps, long now, List<Action> out) {
        for (Opportunity o : opps) {
            Application a = o.application;
            if (a == null || AppStatus.valueOf(a.status).isFinal()) continue;
            int last = o.drive.stages.size() - 1;
            // Stage 0 is the submission itself and the last is the outcome: neither is a round to prepare for.
            if (a.currentStage <= 0 || a.currentStage >= last) continue;
            Stage st = o.drive.stages.get(a.currentStage);
            if (st.scheduledAt == null) continue;
            long until = st.scheduledAt.getTime() - now;
            if (until < 0 || until > STAGE_WINDOW_DAYS * DAY) continue;
            int days = (int) (until / DAY);
            Component prep = stageComponent(st);
            Action act = new Action(Kind.STAGE, 900 - 10 * days,
                    st.name + " · " + o.drive.companyName,
                    "Your " + o.drive.title + " application is at this round, " + when(days)
                            + (st.venueOrLink == null ? "." : " (" + st.venueOrLink + ")."),
                    "Prepare with " + prep.label.toLowerCase(Locale.ROOT) + " practice and log it.");
            act.driveId = o.drive.id;
            act.applicationId = a.id;
            act.component = prep;
            out.add(act);
        }
    }

    /** Which readiness component prepares best for a stage, from its type and name. */
    static Component stageComponent(Stage st) {
        String n = (st.name == null ? "" : st.name).toLowerCase(Locale.ROOT);
        if (n.contains("aptitude")) return Component.APTITUDE;
        if (n.contains("hr") || n.contains("panel") || n.contains("case")
                || ("INTERVIEW".equals(st.type) && !n.contains("technical") && !n.contains("ml"))) return Component.INTERVIEW;
        return Component.CODING; // coding rounds, online assessments, technical interviews
    }

    // ---------------------------------------------------------------- A. application opportunities

    private static void applications(List<? extends Opportunity> opps, long now, List<Action> out) {
        for (Opportunity o : opps) {
            // Only drives the workflow already allows: eligible, open, before deadline, not applied.
            if (o.decision == null || !o.decision.allowed || o.match == null) continue;
            long left = o.drive.deadline.getTime() - now;
            int days = (int) (left / DAY);
            boolean urgent = left <= URGENT_DAYS * DAY;
            Action act = new Action(Kind.APPLY,
                    urgent ? 800 - (int) (left / (60 * 60 * 1000L)) / 6 : 500 + o.match.score - Math.min(days, 30),
                    (urgent ? "Apply soon: " : "Apply: ") + o.drive.title + " · " + o.drive.companyName,
                    "You're eligible with a " + o.match.score + "% match, and applications close " + when(days) + ".",
                    "Open the drive to review details and apply.");
            act.driveId = o.drive.id;
            out.add(act);
        }
    }

    // ---------------------------------------------------------------- B. skill gaps

    /** Drives worth preparing for: eligible (or provisionally so), open or actively in progress. */
    private static List<Opportunity> targets(List<? extends Opportunity> opps, long now) {
        List<Opportunity> t = new ArrayList<>();
        for (Opportunity o : opps) {
            if (o.match == null) continue;
            boolean active = o.application != null && !AppStatus.valueOf(o.application.status).isFinal();
            if ((o.application == null && o.drive.isOpen(now)) || active) t.add(o);
        }
        return t;
    }

    private static void skills(List<Opportunity> targets, List<Action> out) {
        Map<String, List<Opportunity>> bySkill = new LinkedHashMap<>();
        for (Opportunity o : targets) {
            for (String s : o.match.missingSkills) bySkill.computeIfAbsent(s, k -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<String, List<Opportunity>> e : bySkill.entrySet()) {
            List<String> titles = new ArrayList<>();
            for (Opportunity o : e.getValue()) titles.add(o.drive.title);
            int n = e.getValue().size();
            Action act = new Action(Kind.SKILL, 300 + 10 * n, "Build skill: " + e.getKey(),
                    "Required by " + n + (n == 1 ? " drive" : " drives") + " you're eligible for ("
                            + String.join(", ", titles) + ") and missing from your profile.",
                    "Log practice under Skill development; add it to your profile once you can use it.");
            act.skill = e.getKey();
            act.driveId = e.getValue().get(0).drive.id;
            act.component = Component.SKILLS;
            out.add(act);
        }
    }

    // ---------------------------------------------------------------- C. readiness

    private static void readiness(ReadinessAnalyzer.Report rep, List<Opportunity> targets, List<Action> out) {
        if (rep == null) return;
        Map<Component, Integer> focusCount = new EnumMap<>(Component.class);
        for (Opportunity o : targets) {
            for (String f : o.drive.prepFocus) {
                try { focusCount.merge(Component.valueOf(f), 1, Integer::sum); } catch (IllegalArgumentException ignored) { }
            }
        }
        // Weakest component among the target drives' focus areas; without targets, the weakest activity component.
        Component weakest = null;
        for (Component c : focusCount.isEmpty() ? Component.ACTIVITY_TYPES : focusCount.keySet()) {
            if (c == Component.RESUME) continue;
            if (weakest == null || rep.percents.get(c) < rep.percents.get(weakest)) weakest = c;
        }
        if (weakest != null && rep.percents.get(weakest) < WEAK_PERCENT) {
            int pct = rep.percents.get(weakest);
            Integer n = focusCount.get(weakest);
            Action act = new Action(Kind.READINESS, 200 + (WEAK_PERCENT - pct),
                    "Raise " + weakest.label.toLowerCase(Locale.ROOT) + " readiness (" + pct + "%)",
                    n == null ? "It is your weakest preparation area in the last 30 days."
                            : n + (n == 1 ? " drive you're targeting focuses" : " drives you're targeting focus")
                            + " on " + weakest.label.toLowerCase(Locale.ROOT) + ", and your recorded readiness is " + pct + "%.",
                    ReadinessAnalyzer.action(weakest, pct));
            act.component = weakest;
            out.add(act);
        }
        int resume = rep.percents.get(Component.RESUME);
        if (resume < 100) {
            Action act = new Action(Kind.PROFILE, 150, "Complete your resume profile (" + resume + "%)",
                    "Resume completeness counts for 20% of readiness and is what officers see when you apply.",
                    "Add a resume link, 3+ skills, a certification, a summary and a GitHub or LinkedIn link.");
            act.component = Component.RESUME;
            out.add(act);
        }
    }

    private static String when(int days) {
        return days <= 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
    }
}
