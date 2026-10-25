package com.nexora.domain;

import com.nexora.domain.Models.AppStatus;
import com.nexora.domain.Models.Application;
import com.nexora.domain.Models.Drive;
import com.nexora.domain.Models.StudentProfile;

import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/** Client-side officer analytics (suitable for a single-campus dataset). */
public final class Analytics {

    public static final class Dashboard {
        public int openDrives, totalApplications, pendingVerifications, selectionsThisSeason;
    }

    public Dashboard dashboard(List<Drive> drives, List<Application> apps, List<StudentProfile> profiles, long now) {
        Dashboard d = new Dashboard();
        for (Drive drive : drives) if (Models.DriveStatus.OPEN.name().equals(drive.status)) d.openDrives++;
        d.totalApplications = apps.size();
        for (StudentProfile p : profiles) if (!p.academicVerified) d.pendingVerifications++;
        long seasonStart = seasonStart(now);
        for (Application a : apps) {
            Long t = a.updatedAt != null ? a.updatedAt.getTime() : a.appliedAt != null ? a.appliedAt.getTime() : null;
            if (AppStatus.SELECTED.name().equals(a.status) && t != null && t >= seasonStart) d.selectionsThisSeason++;
        }
        return d;
    }

    /** Placement season starts on 1 July of the current academic year. */
    public static long seasonStart(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(now);
        int year = c.get(Calendar.MONTH) >= Calendar.JULY ? c.get(Calendar.YEAR) : c.get(Calendar.YEAR) - 1;
        c.clear();
        c.set(year, Calendar.JULY, 1);
        return c.getTimeInMillis();
    }

    /** Applications per drive, keyed by "Company · Title", in drive order. */
    public Map<String, Integer> applicationsPerDrive(List<Drive> drives, List<Application> apps) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Drive d : drives) {
            int n = 0;
            for (Application a : apps) if (d.id.equals(a.driveId)) n++;
            out.put(d.companyName + " · " + d.title, n);
        }
        return out;
    }

    /** Number of applicants that reached each stage of a drive (stage name → count). */
    public Map<String, Integer> funnel(Drive d, List<Application> apps) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < d.stages.size(); i++) {
            int n = 0;
            for (Application a : apps) {
                if (!d.id.equals(a.driveId)) continue;
                boolean reached = a.currentStage >= i;
                // The final stage counts only selections, not applicants rejected there.
                if (i == d.stages.size() - 1) reached = AppStatus.SELECTED.name().equals(a.status);
                if (reached) n++;
            }
            out.put(d.stages.get(i).name, n);
        }
        return out;
    }

    /** Selected applications per branch (from the profile snapshot taken at apply time). */
    public Map<String, Integer> branchSelections(List<Application> apps) {
        Map<String, Integer> out = new TreeMap<>();
        for (Application a : apps) {
            if (AppStatus.SELECTED.name().equals(a.status)) {
                out.merge(a.snapBranch == null ? "Unknown" : a.snapBranch, 1, Integer::sum);
            }
        }
        return out;
    }

    /** {average readiness of all applicants, average readiness of selected students}; -1 when no data. */
    public int[] readinessComparison(List<Application> apps, Map<String, Integer> overallByStudent) {
        Set<String> applicants = new HashSet<>(), selected = new HashSet<>();
        for (Application a : apps) {
            applicants.add(a.studentId);
            if (AppStatus.SELECTED.name().equals(a.status)) selected.add(a.studentId);
        }
        return new int[]{average(applicants, overallByStudent), average(selected, overallByStudent)};
    }

    private static int average(Set<String> ids, Map<String, Integer> values) {
        int n = 0;
        double sum = 0;
        for (String id : ids) {
            Integer v = values.get(id);
            if (v != null) { sum += v; n++; }
        }
        return n == 0 ? -1 : (int) Math.round(sum / n);
    }
}
