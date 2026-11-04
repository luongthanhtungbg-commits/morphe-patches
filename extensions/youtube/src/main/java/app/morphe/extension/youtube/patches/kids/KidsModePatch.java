/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Kids mode: watch time limit, allowed hours, blocked videos and periodic quiz.
 */
@SuppressWarnings("unused")
public final class KidsModePatch {

    private enum LockReason {
        QUIZ, DAILY_LIMIT, OUTSIDE_HOURS, BLOCKED_VIDEO
    }

    /** Extra time a parent grants with one tap. */
    private static final long PARENT_EXTENSION_MS = 15 * 60_000L;
    /** How often the watch time counter is written to storage. */
    private static final long SAVE_INTERVAL_MS = 10_000L;

    private static final int COLOR_BACKGROUND = Color.parseColor("#FFF8E7");
    private static final int COLOR_TEXT = Color.parseColor("#2D2A32");
    private static final int COLOR_ACCENT = Color.parseColor("#FF7A59");
    private static final int COLOR_OPTION = Color.parseColor("#4F8EF7");
    private static final int COLOR_CORRECT = Color.parseColor("#2EAD5B");
    private static final int COLOR_WRONG = Color.parseColor("#E5484D");

    private static final KidsQuiz quiz = new KidsQuiz();

    private static long lastTickMs;
    private static long lastSaveMs;
    private static long msSinceLastQuiz;
    private static long usedTodayMs = -1;

    @Nullable
    private static Dialog lockDialog;
    @Nullable
    private static LockReason lockReason;
    private static long lockVideoTime = -1;

    private static String lastCheckedVideoId = "";
    private static String skipCheckVideoId = "";

    @Nullable
    private static AudioFocusRequest focusRequest;


    // ---------------------------------------------------------------- Injection point

    /**
     * Injection point. Called repeatedly while a video is playing.
     */
    public static void setVideoTime(long videoTime) {
        try {
            if (!Settings.KIDS_MODE.get()) {
                lastTickMs = 0;
                return;
            }

            final long now = System.currentTimeMillis();

            if (lockDialog != null) {
                lastTickMs = 0;
                holdPlayback(videoTime);
                return;
            }

            rollOverDayIfNeeded();

            final long delta = KidsRules.tickDelta(lastTickMs, now);
            lastTickMs = now;
            usedTodayMs += delta;
            msSinceLastQuiz += delta;
            if (now - lastSaveMs > SAVE_INTERVAL_MS) {
                lastSaveMs = now;
                Settings.KIDS_MODE_USED_TODAY_MS.save(usedTodayMs);
            }

            if (Settings.KIDS_MODE_BLOCK_PLAYBACK.get() && checkCurrentVideoBlocked()) {
                showLock(LockReason.BLOCKED_VIDEO, videoTime);
                return;
            }

            int[] hours = KidsRules.parseHourRange(Settings.KIDS_MODE_ALLOWED_HOURS.get());
            if (!KidsRules.isWithinHours(minuteOfDay(), hours)) {
                showLock(LockReason.OUTSIDE_HOURS, videoTime);
                return;
            }

            final long allowedMs = Settings.KIDS_MODE_EXTRA_TODAY_MS.get();
            if (KidsRules.isDailyLimitReached(usedTodayMs - allowedMs,
                    Settings.KIDS_MODE_DAILY_LIMIT_MINUTES.get())) {
                showLock(LockReason.DAILY_LIMIT, videoTime);
                return;
            }

            if (Settings.KIDS_MODE_QUIZ.get()
                    && KidsRules.isQuizDue(msSinceLastQuiz, Settings.KIDS_MODE_QUIZ_INTERVAL_MINUTES.get())) {
                showLock(LockReason.QUIZ, videoTime);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Kids mode setVideoTime failure", ex);
        }
    }

    // ---------------------------------------------------------------- Rules

    private static boolean checkCurrentVideoBlocked() {
        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty() || videoId.equals(lastCheckedVideoId) || videoId.equals(skipCheckVideoId)) {
            return false;
        }
        String title = VideoInformation.getVideoTitle();
        if (title == null || title.isEmpty()) {
            return false; // Title not loaded yet. Check again on the next tick.
        }
        lastCheckedVideoId = videoId;

        String reason = KidsFeedRules.blockReason(title + " " + VideoInformation.getChannelName());
        if (reason != null) {
            Logger.printDebug(() -> "Kids mode blocked video: " + videoId + " reason: " + reason);
            return true;
        }
        return false;
    }

    private static void rollOverDayIfNeeded() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(Settings.KIDS_MODE_USED_DAY.get())) {
            Settings.KIDS_MODE_USED_DAY.save(today);
            Settings.KIDS_MODE_USED_TODAY_MS.save(0L);
            Settings.KIDS_MODE_EXTRA_TODAY_MS.save(0L);
            usedTodayMs = 0;
        } else if (usedTodayMs < 0) {
            usedTodayMs = Settings.KIDS_MODE_USED_TODAY_MS.get();
        }
    }

    private static int minuteOfDay() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    // ---------------------------------------------------------------- Playback control

    /**
     * Pauses the video by taking audio focus, which YouTube treats like an incoming call.
     */
    private static void pausePlayback() {
        try {
            AudioManager audioManager = audioManager();
            if (audioManager == null || focusRequest != null) return;
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ex) {
            Logger.printException(() -> "pausePlayback failure", ex);
        }
    }

    private static void resumePlayback() {
        try {
            AudioManager audioManager = audioManager();
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "resumePlayback failure", ex);
        } finally {
            focusRequest = null;
        }
    }

    /**
     * Fallback if the video keeps playing behind the dialog: keep it at the lock position.
     */
    private static void holdPlayback(long videoTime) {
        if (lockVideoTime >= 0 && Math.abs(videoTime - lockVideoTime) > 1500) {
            final long target = lockVideoTime;
            // Post to avoid a recursive call from inside the video time hook.
            Utils.runOnMainThread(() -> VideoInformation.seekTo(target));
        }
    }

    @Nullable
    private static AudioManager audioManager() {
        Context context = Utils.getContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---------------------------------------------------------------- Lock screen

    private static void showLock(LockReason reason, long videoTime) {
        if (lockDialog != null) return;
        lockVideoTime = videoTime;
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || lockDialog != null) return;
            pausePlayback();
            lockReason = reason;
            lockDialog = buildDialog(activity, reason);
            lockDialog.show();
        });
    }

    private static void unlock(boolean resetQuizTimer) {
        Dialog dialog = lockDialog;
        lockDialog = null;
        lockReason = null;
        lockVideoTime = -1;
        if (resetQuizTimer) msSinceLastQuiz = 0;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        resumePlayback();
    }

    private static Dialog buildDialog(Activity activity, LockReason reason) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(COLOR_BACKGROUND);
        final int pad = dp(activity, 24);
        root.setPadding(pad, pad, pad, pad);

        if (reason == LockReason.QUIZ) {
            buildQuiz(activity, root);
        } else {
            buildMessage(activity, root, reason);
        }

        Button parent = textButton(activity, "Phụ huynh");
        parent.setOnClickListener(v -> askParentPin(activity, () -> showParentMenu(activity)));
        LinearLayout.LayoutParams parentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parentParams.topMargin = dp(activity, 32);
        root.addView(parent, parentParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        return dialog;
    }

    private static void buildMessage(Activity activity, LinearLayout root, LockReason reason) {
        final String emoji, title, body;
        switch (reason) {
            case DAILY_LIMIT -> {
                emoji = "⏰";
                title = "Hết giờ xem hôm nay rồi!";
                body = "Con đã xem " + KidsRules.formatMinutes(usedTodayMs)
                        + ". Mình nghỉ mắt, đi chơi hoặc đọc sách nhé.";
            }
            case OUTSIDE_HOURS -> {
                emoji = "🌙";
                title = "Chưa đến giờ xem video";
                body = "Con chỉ được xem trong khung giờ " + Settings.KIDS_MODE_ALLOWED_HOURS.get() + ".";
            }
            default -> {
                emoji = "🙈";
                title = "Video này không phù hợp";
                body = "Mình chọn video khác nhé!";
            }
        }

        root.addView(text(activity, emoji, 64, false));
        root.addView(text(activity, title, 26, true), marginTop(activity, 12));
        root.addView(text(activity, body, 18, false), marginTop(activity, 12));

        if (reason == LockReason.BLOCKED_VIDEO) {
            Button back = optionButton(activity, "Quay lại", COLOR_ACCENT);
            back.setOnClickListener(v -> {
                lastCheckedVideoId = ""; // Block again if the same video is reopened.
                unlock(false);
                activity.onBackPressed();
            });
            root.addView(back, marginTop(activity, 28));
        }
    }

    // ---------------------------------------------------------------- Quiz

    private static void buildQuiz(Activity activity, LinearLayout root) {
        final int total = Math.max(1, Math.min(10, Settings.KIDS_MODE_QUIZ_QUESTIONS.get()));

        TextView header = text(activity, "🧠 Trả lời đúng để xem tiếp!", 22, true);
        TextView progress = text(activity, "", 16, false);
        TextView prompt = text(activity, "", 30, true);
        TextView feedback = text(activity, "", 18, true);
        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);

        root.addView(header);
        root.addView(progress, marginTop(activity, 8));
        root.addView(prompt, marginTop(activity, 24));
        root.addView(options, marginTop(activity, 20));
        root.addView(feedback, marginTop(activity, 12));

        new Object() {
            int correctCount;

            void nextQuestion() {
                progress.setText("Câu " + (correctCount + 1) + "/" + total);
                KidsQuiz.Question question = quiz.next(
                        Settings.KIDS_MODE_QUIZ_MATH.get(),
                        Settings.KIDS_MODE_QUIZ_ENGLISH.get(),
                        Settings.KIDS_MODE_QUIZ_MATH_LEVEL.get(),
                        Settings.KIDS_MODE_QUIZ_CUSTOM_VOCAB.get());
                prompt.setText(question.prompt);
                options.removeAllViews();

                // 2 x 2 grid of big buttons.
                LinearLayout row = null;
                for (int i = 0; i < question.options.length; i++) {
                    if (i % 2 == 0) {
                        row = new LinearLayout(activity);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        options.addView(row, marginTop(activity, 10));
                    }
                    final int index = i;
                    Button button = optionButton(activity, question.options[i], COLOR_OPTION);
                    button.setOnClickListener(v -> onAnswer(question, index, button));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(activity, 72), 1f);
                    params.setMargins(dp(activity, 6), 0, dp(activity, 6), 0);
                    row.addView(button, params);
                }
            }

            void onAnswer(KidsQuiz.Question question, int index, Button button) {
                if (question.isCorrect(index)) {
                    correctCount++;
                    tint(button, COLOR_CORRECT);
                    feedback.setTextColor(COLOR_CORRECT);
                    feedback.setText(correctCount >= total ? "🎉 Giỏi quá! Xem tiếp thôi!" : "✅ Đúng rồi!");
                    setOptionsEnabled(false);
                    if (correctCount >= total) {
                        options.postDelayed(() -> unlock(true), 900);
                    } else {
                        options.postDelayed(() -> {
                            feedback.setText("");
                            nextQuestion();
                        }, 700);
                    }
                } else {
                    tint(button, COLOR_WRONG);
                    button.setEnabled(false);
                    feedback.setTextColor(COLOR_WRONG);
                    feedback.setText("❌ Chưa đúng, con thử lại nhé!");
                }
            }

            void setOptionsEnabled(boolean enabled) {
                for (int r = 0; r < options.getChildCount(); r++) {
                    ViewGroup row = (ViewGroup) options.getChildAt(r);
                    for (int c = 0; c < row.getChildCount(); c++) row.getChildAt(c).setEnabled(enabled);
                }
            }
        }.nextQuestion();
    }

    // ---------------------------------------------------------------- Parent controls

    /**
     * Runs {@code onSuccess} if no PIN is set or the correct PIN is entered.
     */
    public static void askParentPin(Activity activity, Runnable onSuccess) {
        final String pin = Settings.KIDS_MODE_PIN.get();
        if (pin.isEmpty()) {
            onSuccess.run();
            return;
        }

        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Mã PIN phụ huynh");

        new AlertDialog.Builder(activity)
                .setTitle("Nhập mã PIN phụ huynh")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (pin.equals(input.getText().toString().trim())) {
                        onSuccess.run();
                    } else {
                        Utils.showSnackbarShort("Sai mã PIN");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showParentMenu(Activity activity) {
        final LockReason reason = lockReason;
        final String[] items = {
                reason == LockReason.QUIZ ? "Bỏ qua câu hỏi lần này" : "Mở khóa lần này",
                "Cho thêm 15 phút hôm nay",
                "Tắt chế độ trẻ em"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Phụ huynh")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> {
                            if (reason == LockReason.BLOCKED_VIDEO) {
                                skipCheckVideoId = VideoInformation.getVideoId();
                            }
                            unlock(reason == LockReason.QUIZ);
                        }
                        case 1 -> {
                            Settings.KIDS_MODE_EXTRA_TODAY_MS.save(
                                    Settings.KIDS_MODE_EXTRA_TODAY_MS.get() + PARENT_EXTENSION_MS);
                            unlock(false);
                            Utils.showSnackbarShort("Đã cho thêm 15 phút");
                        }
                        default -> {
                            Settings.KIDS_MODE.save(false);
                            unlock(true);
                            Utils.showSnackbarShort("Đã tắt chế độ trẻ em");
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Injection point from the settings screen. Closes settings unless the parent PIN is entered.
     */
    public static void guardSettings(Activity settingsActivity) {
        try {
            if (!Settings.KIDS_MODE.get() || Settings.KIDS_MODE_PIN.get().isEmpty()) return;

            EditText input = new EditText(settingsActivity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.setHint("Mã PIN phụ huynh");

            new AlertDialog.Builder(settingsActivity)
                    .setTitle("Cài đặt đã được khóa")
                    .setMessage("Nhập mã PIN phụ huynh để mở cài đặt.")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (!Settings.KIDS_MODE_PIN.get().equals(input.getText().toString().trim())) {
                            Utils.showSnackbarShort("Sai mã PIN");
                            settingsActivity.finish();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> settingsActivity.finish())
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "guardSettings failure", ex);
        }
    }

    // ---------------------------------------------------------------- View helpers

    private static int dp(Context context, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams marginTop(Context context, int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, dp);
        return params;
    }

    private static TextView text(Context context, String value, int sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static Button optionButton(Context context, String label, int color) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        tint(button, color);
        return button;
    }

    private static Button textButton(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.parseColor("#8A8494"));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return button;
    }

    private static void tint(View view, int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(view.getContext(), 18));
        view.setBackground(background);
    }
}
