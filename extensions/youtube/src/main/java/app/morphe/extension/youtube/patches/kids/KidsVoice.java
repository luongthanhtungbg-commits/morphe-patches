/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Speaks short Vietnamese phrases to the child using the device text to speech engine.
 * A phrase requested before the engine is ready is spoken as soon as it is.
 */
final class KidsVoice {

    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");

    @Nullable
    private static TextToSpeech tts;
    private static boolean ready;
    @Nullable
    private static String pending;

    private KidsVoice() {
    }

    static void speak(Context context, String text) {
        try {
            if (tts == null) {
                pending = text;
                tts = new TextToSpeech(context.getApplicationContext(), status -> {
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        Logger.printDebug(() -> "Kids mode text to speech is not available: " + status);
                        return;
                    }
                    final int result = tts.setLanguage(VIETNAMESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Logger.printDebug(() -> "Vietnamese voice is not installed, using the default voice");
                    }
                    tts.setSpeechRate(0.9f);
                    tts.setPitch(1.15f);
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    ready = true;
                    String queued = pending;
                    pending = null;
                    if (queued != null) say(queued);
                });
                return;
            }

            if (ready) {
                say(text);
            } else {
                pending = text;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Kids mode speak failure", ex);
        }
    }

    static void stop() {
        pending = null;
        try {
            if (tts != null && ready) tts.stop();
        } catch (Exception ex) {
            Logger.printException(() -> "Kids mode stop speech failure", ex);
        }
    }

    private static void say(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kids_mode");
    }
}
