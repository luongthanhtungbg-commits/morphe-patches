/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.youtube.settings.Settings;

/**
 * Applies the parent's keyword settings. Shared by the feed filter and the player check.
 * Thread safe, because Litho filters run on multiple threads.
 */
public final class KidsFeedRules {

    private static final class Parsed {
        final String blockedRaw, allowedRaw;
        final List<String> blocked, allowed;

        Parsed(String blockedRaw, String allowedRaw) {
            this.blockedRaw = blockedRaw;
            this.allowedRaw = allowedRaw;
            this.blocked = KidsRules.parseKeywords(blockedRaw);
            this.allowed = KidsRules.parseKeywords(allowedRaw);
        }
    }

    private static volatile Parsed parsed = new Parsed("", "");

    private KidsFeedRules() {
    }

    /**
     * @return Why the text is blocked, or null if it is allowed.
     */
    @Nullable
    public static String blockReason(String text) {
        String blockedRaw = Settings.KIDS_MODE_BLOCKED_KEYWORDS.get();
        String allowedRaw = Settings.KIDS_MODE_ALLOWED_KEYWORDS.get();
        Parsed current = parsed;
        if (!blockedRaw.equals(current.blockedRaw) || !allowedRaw.equals(current.allowedRaw)) {
            current = new Parsed(blockedRaw, allowedRaw);
            parsed = current; // Benign race: another thread may parse the same values.
        }
        return KidsRules.blockReason(text, current.blocked, current.allowed);
    }
}
