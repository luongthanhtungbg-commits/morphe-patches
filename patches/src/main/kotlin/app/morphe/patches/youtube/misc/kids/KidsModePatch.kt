/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.patches.youtube.misc.kids

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/kids/KidsModePatch;"
private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/KidsContentFilter;"
private const val SEEKBAR_TAG =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

@Suppress("unused")
val kidsModePatch = bytecodePatch(
    name = "Kids mode",
    description = "Adds a parent controlled kids mode: keyword based content filtering, " +
            "daily watch time limit, allowed hours and a Math/English quiz that must be answered to keep watching.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_kids_mode_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_kids_mode", summary = true),
                    TextPreference("morphe_kids_mode_pin", inputType = InputType.NUMBER),
                    PreferenceCategory(
                        key = "morphe_kids_mode_content_category",
                        sorting = Sorting.UNSORTED,
                        preferences = setOf(
                            TextPreference(
                                "morphe_kids_mode_blocked_keywords",
                                inputType = InputType.TEXT_MULTI_LINE
                            ),
                            TextPreference(
                                "morphe_kids_mode_allowed_keywords",
                                inputType = InputType.TEXT_MULTI_LINE
                            ),
                            SwitchPreference("morphe_kids_mode_hide_shorts", summary = true),
                            SwitchPreference("morphe_kids_mode_block_playback", summary = true),
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_kids_mode_time_category",
                        sorting = Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_kids_mode_daily_limit_minutes",
                                tag = SEEKBAR_TAG
                            ),
                            TextPreference("morphe_kids_mode_allowed_hours"),
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_kids_mode_quiz_category",
                        sorting = Sorting.UNSORTED,
                        preferences = setOf(
                            SwitchPreference("morphe_kids_mode_quiz", summary = true),
                            NonInteractivePreference(
                                key = "morphe_kids_mode_quiz_interval_minutes",
                                tag = SEEKBAR_TAG
                            ),
                            NonInteractivePreference(
                                key = "morphe_kids_mode_quiz_questions",
                                tag = SEEKBAR_TAG
                            ),
                            SwitchPreference("morphe_kids_mode_quiz_math"),
                            NonInteractivePreference(
                                key = "morphe_kids_mode_quiz_math_level",
                                tag = SEEKBAR_TAG
                            ),
                            SwitchPreference("morphe_kids_mode_quiz_english"),
                            TextPreference(
                                "morphe_kids_mode_quiz_custom_vocab",
                                inputType = InputType.TEXT_MULTI_LINE
                            ),
                        )
                    ),
                )
            )
        )

        addLithoFilter(EXTENSION_FILTER)

        videoTimeHook(EXTENSION_CLASS, "setVideoTime")
    }
}
