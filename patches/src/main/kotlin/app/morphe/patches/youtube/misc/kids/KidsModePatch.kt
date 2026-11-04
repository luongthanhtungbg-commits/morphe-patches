/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.patches.youtube.misc.kids

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
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
import org.w3c.dom.Element

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/kids/KidsModePatch;"
private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/KidsContentFilter;"
private const val SEEKBAR_TAG =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

private const val CAMERA_PERMISSION = "android.permission.CAMERA"

/**
 * The screen distance check uses the front camera, so the app must declare the camera permission.
 */
private val kidsModeCameraPermissionPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val permissions = document.getElementsByTagName("uses-permission")
            val declared = (0 until permissions.length).any {
                (permissions.item(it) as Element).getAttribute("android:name") == CAMERA_PERMISSION
            }
            if (!declared) {
                manifest.appendChild(
                    document.createElement("uses-permission").apply {
                        setAttribute("android:name", CAMERA_PERMISSION)
                    }
                )
            }
        }
    }
}

@Suppress("unused")
val kidsModePatch = bytecodePatch(
    name = "Kids mode",
    description = "Adds a parent controlled kids mode: keyword based content filtering, " +
            "daily watch time limit, allowed hours, a spoken math quiz that must be answered to keep watching, " +
            "and a front camera check that pauses the video when the child sits too close to the screen.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        videoInformationPatch,
        kidsModeCameraPermissionPatch,
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
                    SwitchPreference("morphe_kids_mode_quiz_voice", summary = true),
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
                        key = "morphe_kids_mode_distance_category",
                        sorting = Sorting.UNSORTED,
                        preferences = setOf(
                            SwitchPreference("morphe_kids_mode_distance", summary = true),
                            NonInteractivePreference(
                                key = "morphe_kids_mode_distance_min_cm",
                                tag = SEEKBAR_TAG
                            ),
                            SwitchPreference("morphe_kids_mode_distance_saver", summary = true),
                            SwitchPreference("morphe_kids_mode_distance_show", summary = true),
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
                            NonInteractivePreference(
                                key = "morphe_kids_mode_quiz_math_level",
                                tag = SEEKBAR_TAG
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
