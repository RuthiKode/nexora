package com.nexora.domain;

import com.nexora.domain.Models.Activity;
import com.nexora.domain.Models.Component;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StudentProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Placement readiness from the profile and self-logged activities of the last 30 days.
 * Readiness reflects recorded preparation, not the chance of getting an offer.
 */
public final class ReadinessAnalyzer {

    public static final long DAY = 24L * 60 * 60 * 1000;
    public static final int WINDOW_DAYS = 30;
    public static final String DISCLAIMER =
            "Readiness reflects your recorded preparation, not your chance of getting an offer.";

    public static final class WeeklyPoint {
        public final long weekStart;
        public final int activities, minutes;

        WeeklyPoint(long weekStart, int activities, int minutes) {
            this.weekStart = weekStart; this.activities = activities; this.minutes = minutes;
        }
    }

    public static final class Report {
        public int overall;
        public final Map<Component, Integer> percents = new EnumMap<>(Component.class);
        public final List<WeeklyPoint> last6Weeks = new ArrayList<>();
        public final List<String> suggestions = new ArrayList<>();
        public final List<String> relevantDriveIds = new ArrayList<>();
        public int activitiesInWindow;
    }

    /**
     * @param eligibleDrives open drives the student is eligible for (used for the drive-linked suggestion)
     */
    public Report analyze(StudentProfile p, List<Activity> activities, List<Drive> eligibleDrives, long now) {
        Report rep = new Report();
        List<Activity> window = new ArrayList<>();
        for (Activity a : activities) {
            if (a.date == null) continue;
            long daysAgo = Math.floorDiv(now - a.date.getTime(), DAY);
            if (daysAgo >= 0 && daysAgo <= WINDOW_DAYS) window.add(a);
        }
        rep.activitiesInWindow = window.size();

        rep.percents.put(Component.RESUME, resumePercent(p));
        rep.percents.put(Component.APTITUDE, scored(window, Component.APTITUDE));
        rep.percents.put(Component.CODING, scored(window, Component.CODING));
        rep.percents.put(Component.INTERVIEW, interview(window));
        rep.percents.put(Component.SKILLS, skills(window));

        double total = 0;
        for (Component c : Component.values()) total += c.weight * rep.percents.get(c) / 100.0;
        rep.overall = (int) Math.round(total);

        weekly(rep, activities, now);
        suggest(rep, eligibleDrives, now);
        return rep;
    }

    static int resumePercent(StudentProfile p) {
        int done = 0;
        if (!EligibilityEngine.isBlank(p.resumeUrl)) done++;
        if (p.skills != null && p.skills.size() >= 3) done++;
        if (p.certifications != null && !p.certifications.isEmpty()) done++;
        if (!EligibilityEngine.isBlank(p.summary)) done++;
        if (!EligibilityEngine.isBlank(p.githubUrl) || !EligibilityEngine.isBlank(p.linkedinUrl)) done++;
        return Math.round(done * 100f / 5);
    }

    /** avg(scorePercent) × min(count / 4, 1) for aptitude and coding. */
    private static int scored(List<Activity> window, Component type) {
        int count = 0, sum = 0;
        for (Activity a : window) {
            if (type.name().equals(a.type) && a.scorePercent != null) { count++; sum += a.scorePercent; }
        }
        if (count == 0) return 0;
        return (int) Math.round((double) sum / count * Math.min(count / 4.0, 1.0));
    }

    /** (avg rating / 5 × 100) × min(count / 2, 1). */
    private static int interview(List<Activity> window) {
        int count = 0, sum = 0;
        for (Activity a : window) {
            if (Component.INTERVIEW.name().equals(a.type) && a.rating != null) { count++; sum += a.rating; }
        }
        if (count == 0) return 0;
        return (int) Math.round((double) sum / count / 5.0 * 100.0 * Math.min(count / 2.0, 1.0));
    }

    /** min(total minutes / 600, 1) × 100. */
    private static int skills(List<Activity> window) {
        int minutes = 0;
        for (Activity a : window) if (Component.SKILLS.name().equals(a.type)) minutes += a.durationMin;
        return (int) Math.round(Math.min(minutes / 600.0, 1.0) * 100);
    }

    /** Bucket 0 is the 7 days ending now; bucket 5 is 35–41 days ago. Oldest first in the result. */
    private static void weekly(Report rep, List<Activity> all, long now) {
        int[] count = new int[6], minutes = new int[6];
        for (Activity a : all) {
            if (a.date == null) continue;
            long diff = now - a.date.getTime();
            if (diff < 0) continue;
            int bucket = (int) (diff / (7 * DAY));
            if (bucket < 6) { count[bucket]++; minutes[bucket] += a.durationMin; }
        }
        for (int i = 5; i >= 0; i--) {
            rep.last6Weeks.add(new WeeklyPoint(now - (i + 1) * 7 * DAY, count[i], minutes[i]));
        }
    }

    private static void suggest(Report rep, List<Drive> drives, long now) {
        List<Component> sorted = new ArrayList<>(rep.percents.keySet());
        sorted.sort(Comparator.comparingInt(rep.percents::get));
        List<Component> weakest = sorted.subList(0, 2);

        // Drive-linked suggestion: the focus area shared by most eligible drives closing within 7 days.
        List<Drive> upcoming = new ArrayList<>();
        for (Drive d : drives) {
            if (d.deadline != null && d.deadline.getTime() >= now) upcoming.add(d);
        }
        upcoming.sort(Comparator.comparingLong(d -> d.deadline.getTime()));
        Map<Component, Integer> focusCount = new EnumMap<>(Component.class);
        for (Drive d : upcoming) {
            if (d.deadline.getTime() - now > 7 * DAY) continue;
            for (String f : d.prepFocus) {
                try {
                    Component c = Component.valueOf(f);
                    focusCount.merge(c, 1, Integer::sum);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Component hot = null;
        for (Map.Entry<Component, Integer> e : focusCount.entrySet()) {
            if (hot == null || e.getValue() > focusCount.get(hot)
                    || (e.getValue().equals(focusCount.get(hot)) && rep.percents.get(e.getKey()) < rep.percents.get(hot))) {
                hot = e.getKey();
            }
        }
        if (hot != null && rep.percents.get(hot) < 60) {
            int n = focusCount.get(hot);
            rep.suggestions.add(n + (n == 1 ? " eligible drive closes" : " eligible drives close")
                    + " in the next 7 days and focus" + (n == 1 ? "es" : "") + " on " + hot.name()
                    + "; your " + hot.label.toLowerCase(Locale.ROOT) + " readiness is " + rep.percents.get(hot) + "%.");
        }
        for (Component c : weakest) {
            if (rep.percents.get(c) < 100) rep.suggestions.add(action(c, rep.percents.get(c)));
        }

        for (Drive d : upcoming) {
            boolean relevant = d.prepFocus.isEmpty();
            for (Component c : weakest) relevant |= d.prepFocus.contains(c.name());
            if (hot != null) relevant |= d.prepFocus.contains(hot.name());
            if (relevant && rep.relevantDriveIds.size() < 5) rep.relevantDriveIds.add(d.id);
        }
    }

    static String action(Component c, int pct) {
        switch (c) {
            case RESUME: return "Resume completeness is " + pct + "% — add a resume link, 3+ skills, a certification, a summary and a GitHub or LinkedIn link.";
            case APTITUDE: return "Aptitude is " + pct + "% — take at least 4 timed aptitude tests this month and log your scores.";
            case CODING: return "Coding is " + pct + "% — solve and log at least 4 coding practice sets this month.";
            case INTERVIEW: return "Mock interviews are at " + pct + "% — schedule 2 mock interviews and record your rating.";
            default: return "Skill development is " + pct + "% — spend 10 hours this month on skills your target drives require.";
        }
    }
}
