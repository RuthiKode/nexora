package com.nexora.domain;

import com.nexora.domain.Models.Stage;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/** Input validation. Every method returns null when valid, otherwise a user-facing error message. */
public final class Validators {
    private Validators() {}

    public static final String DEFAULT_ROLL_PATTERN = "^[A-Z0-9.\\-]{5,20}$";
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern URL = Pattern.compile("^https?://[^\\s/$.?#][^\\s]*\\.[^\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CGPA = Pattern.compile("^\\d{1,2}(\\.\\d{1,2})?$");

    public static String name(String s) {
        if (blank(s)) return "Enter your name";
        return s.trim().length() > 60 ? "Name is too long" : null;
    }

    public static String email(String s) {
        if (blank(s)) return "Enter your email";
        return EMAIL.matcher(s.trim()).matches() ? null : "Enter a valid email address";
    }

    public static String password(String s) {
        if (s == null || s.isEmpty()) return "Enter a password";
        return s.length() < 8 ? "Password must be at least 8 characters" : null;
    }

    public static String cgpa(String s) {
        if (blank(s)) return "Enter your CGPA";
        if (!CGPA.matcher(s.trim()).matches()) return "CGPA must be a number with at most 2 decimals";
        double v = Double.parseDouble(s.trim());
        return v < 0 || v > 10 ? "CGPA must be between 0 and 10" : null;
    }

    public static String minCgpa(String s) {
        String e = cgpa(s);
        return e == null ? null : e.replace("your CGPA", "the minimum CGPA");
    }

    public static String backlogs(String s) {
        Integer v = intOrNull(s);
        if (v == null) return "Enter a whole number";
        return v < 0 || v > 30 ? "Backlogs must be between 0 and 30" : null;
    }

    public static String graduationYear(String s, int currentYear) {
        Integer v = intOrNull(s);
        if (v == null) return "Enter a year";
        return v < currentYear || v > currentYear + 5
                ? "Graduation year must be between " + currentYear + " and " + (currentYear + 5) : null;
    }

    public static String rollNumber(String s) { return rollNumber(s, DEFAULT_ROLL_PATTERN); }

    public static String rollNumber(String s, String pattern) {
        if (blank(s)) return "Enter your roll number";
        return s.trim().matches(pattern) ? null : "Use 5–20 capital letters, digits, '.' or '-'";
    }

    /** Optional URL: empty is valid; otherwise it must be http(s). */
    public static String url(String s) {
        if (blank(s)) return null;
        return URL.matcher(s.trim()).matches() ? null : "Enter a valid http(s) link";
    }

    public static String requiredUrl(String s) {
        return blank(s) ? "Enter a link" : url(s);
    }

    public static String deadline(Date deadline, long now) {
        if (deadline == null) return "Choose a deadline";
        return deadline.getTime() <= now ? "Deadline must be in the future" : null;
    }

    public static String packageLpa(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return v < 0 || v > 200 ? "Package must be between 0 and 200 LPA" : null;
        } catch (RuntimeException e) {
            return "Enter the package in LPA";
        }
    }

    public static String branches(Collection<String> b) {
        return b == null || b.isEmpty() ? "Select at least one branch" : null;
    }

    public static String graduationYears(Collection<Integer> y) {
        return y == null || y.isEmpty() ? "Select at least one graduation year" : null;
    }

    public static String stages(List<Stage> stages) {
        if (stages == null || stages.size() < 2) return "A drive needs at least 2 stages";
        if (!Models.FIRST_STAGE.equals(stages.get(0).name)) {
            return "The first stage must be \"" + Models.FIRST_STAGE + "\"";
        }
        for (Stage s : stages) if (blank(s.name)) return "Every stage needs a name";
        return null;
    }

    public static String required(String s, String field) {
        return blank(s) ? "Enter " + field : null;
    }

    /** Activity log entry: aptitude/coding need a score, interviews a 1–5 rating, everything a duration. */
    public static String activity(String type, Integer score, Integer rating, int durationMin, Date date, long now) {
        if (date == null) return "Choose a date";
        if (date.getTime() > now) return "Date cannot be in the future";
        if (durationMin < 1 || durationMin > 24 * 60) return "Duration must be between 1 and 1440 minutes";
        if ((Models.Component.APTITUDE.name().equals(type) || Models.Component.CODING.name().equals(type))
                && (score == null || score < 0 || score > 100)) return "Score must be between 0 and 100";
        if (Models.Component.INTERVIEW.name().equals(type) && (rating == null || rating < 1 || rating > 5)) {
            return "Rating must be between 1 and 5";
        }
        return null;
    }

    public static Integer intOrNull(String s) {
        try {
            return blank(s) ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
