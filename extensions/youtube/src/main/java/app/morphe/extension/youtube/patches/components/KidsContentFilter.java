/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.components;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.kids.KidsFeedRules;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Hides feed, search and related videos that do not pass the kids mode keyword rules,
 * and optionally hides all Shorts.
 * <p>
 * Unlike {@link KeywordContentFilter}, this filter has no "too broad" safety backoff,
 * because an allow list is expected to hide most videos.
 */
@SuppressWarnings("unused")
public final class KidsContentFilter extends Filter {

    /** Components whose path starts with a video lockup. Only checked for the root component. */
    private final StringFilterGroup videoLockups = new StringFilterGroup(
            Settings.KIDS_MODE,
            "home_video_with_context.e",
            "search_video_with_context.e",
            "video_with_context.e",
            "related_video_with_context.e",
            "video_lockup_with_attachment.e",
            "compact_video.e"
    );

    private final StringFilterGroup shorts = new StringFilterGroup(
            Settings.KIDS_MODE,
            "inline_shorts",
            "shorts_video_cell",
            "shorts_pivot_item.e",
            "shorts_lockup_cell.e",
            "shorts_shelf",
            "reels_player_overlay"
    );

    public KidsContentFilter() {
        addPathCallbacks(videoLockups, shorts);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (matchedGroup == shorts) {
            return Settings.KIDS_MODE_HIDE_SHORTS.get();
        }

        // Only the outer lockup carries the full title and channel text.
        if (contentIndex != 0 || buffer == null || buffer.length == 0) {
            return false;
        }

        String text = new String(buffer, StandardCharsets.UTF_8);
        String reason = KidsFeedRules.blockReason(text);
        if (reason != null) {
            Logger.printDebug(() -> "Kids mode hiding: " + path + " reason: " + reason);
            return true;
        }
        return false;
    }
}
