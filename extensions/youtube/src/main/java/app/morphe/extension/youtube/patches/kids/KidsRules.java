/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Content and time rules for kids mode.
 * Pure Java with no Android dependencies, so it can be unit tested.
 */
public final class KidsRules {

    /** Keywords shorter than this are ignored, otherwise they match nearly everything. */
    public static final int MINIMUM_KEYWORD_LENGTH = 2;

    /** Ticks further apart than this are treated as a pause, not as watch time. */
    public static final long MAX_TICK_DELTA_MS = 3_000;

    // ---------------------------------------------------------------- Keywords

    public static List<String> parseKeywords(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<String> keywords = new ArrayList<>();
        for (String item : raw.split("[\\n,;]")) {
            String keyword = item.trim().toLowerCase(Locale.ROOT);
            if (keyword.length() >= MINIMUM_KEYWORD_LENGTH && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    /**
     * @param lowerCaseText Text already converted to lower case.
     * @return The first matching keyword, or null.
     */
    public static String findMatch(String lowerCaseText, List<String> keywords) {
        if (lowerCaseText == null) return null;
        for (String keyword : keywords) {
            if (lowerCaseText.contains(keyword)) return keyword;
        }
        return null;
    }

    /**
     * Block list always wins. If an allow list is set, content must match at least one allowed keyword.
     *
     * @return Reason the content is blocked, or null if allowed.
     */
    public static String blockReason(String text, List<String> blocked, List<String> allowed) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String blockedMatch = findMatch(lower, blocked);
        if (blockedMatch != null) return "blocked:" + blockedMatch;
        if (!allowed.isEmpty() && findMatch(lower, allowed) == null) return "not_allowed";
        return null;
    }

    // ---------------------------------------------------------------- Allowed hours

    /**
     * Parses "HH:mm-HH:mm" into minutes of the day.
     *
     * @return {start, end} or null if empty or invalid (meaning no restriction).
     */
    public static int[] parseHourRange(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("-");
        if (parts.length != 2) return null;
        int start = parseClock(parts[0]);
        int end = parseClock(parts[1]);
        if (start < 0 || end < 0 || start == end) return null;
        return new int[]{start, end};
    }

    static int parseClock(String raw) {
        String[] hm = raw.trim().split(":");
        try {
            int hour = Integer.parseInt(hm[0].trim());
            int minute = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
            if (hour < 0 || hour > 24 || minute < 0 || minute > 59) return -1;
            return Math.min(hour * 60 + minute, 24 * 60);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Supports ranges that cross midnight, such as 20:00-07:00. */
    public static boolean isWithinHours(int minuteOfDay, int[] range) {
        if (range == null) return true;
        final int start = range[0], end = range[1];
        return start < end
                ? minuteOfDay >= start && minuteOfDay < end
                : minuteOfDay >= start || minuteOfDay < end;
    }

    // ---------------------------------------------------------------- Watch time

    /**
     * Converts the wall clock gap between two playback ticks into watch time.
     */
    public static long tickDelta(long lastTickMs, long nowMs) {
        if (lastTickMs <= 0 || nowMs <= lastTickMs) return 0;
        long delta = nowMs - lastTickMs;
        return delta > MAX_TICK_DELTA_MS ? 0 : delta;
    }

    public static boolean isDailyLimitReached(long usedMs, int limitMinutes) {
        return limitMinutes > 0 && usedMs >= limitMinutes * 60_000L;
    }

    public static boolean isQuizDue(long msSinceLastQuiz, int intervalMinutes) {
        return intervalMinutes > 0 && msSinceLastQuiz >= intervalMinutes * 60_000L;
    }

    // ---------------------------------------------------------------- Screen distance

    /** Average distance between the eye centers of a young child, in millimeters. */
    public static final float CHILD_EYE_DISTANCE_MM = 52f;

    /** Extra distance needed to unlock, so the lock does not flicker at the threshold. */
    public static final int DISTANCE_HYSTERESIS_CM = 3;

    /**
     * Focal length in pixels of an image whose width spans the sensor width.
     */
    public static float focalLengthPixels(float focalLengthMm, float sensorWidthMm, int imageWidthPx) {
        if (sensorWidthMm <= 0) return 0;
        return focalLengthMm / sensorWidthMm * imageWidthPx;
    }

    /**
     * Pinhole camera estimate: distance = focal length (px) * real size / size in image (px).
     *
     * @return Distance in centimeters, or -1 if it cannot be estimated.
     */
    public static float estimateDistanceCm(float focalLengthPx, float eyeDistancePx, float eyeDistanceMm) {
        if (focalLengthPx <= 0 || eyeDistancePx <= 0) return -1;
        return focalLengthPx * eyeDistanceMm / eyeDistancePx / 10f;
    }

    public static String formatMinutes(long ms) {
        long minutes = Math.max(0, ms) / 60_000L;
        return minutes + " phút";
    }
}
