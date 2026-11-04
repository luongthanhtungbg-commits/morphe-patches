/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Generates simple multiple choice questions (Math and English vocabulary).
 * Pure Java with no Android dependencies, so it can be unit tested.
 */
public final class KidsQuiz {

    public static final int MATH_LEVEL_MIN = 1;
    public static final int MATH_LEVEL_MAX = 4;
    public static final int OPTION_COUNT = 4;

    public static final class Question {
        public final String prompt;
        public final String[] options;
        public final int correctIndex;

        Question(String prompt, String[] options, int correctIndex) {
            this.prompt = prompt;
            this.options = options;
            this.correctIndex = correctIndex;
        }

        public boolean isCorrect(int optionIndex) {
            return optionIndex == correctIndex;
        }
    }

    /** English word and its Vietnamese meaning. */
    public static final class Word {
        public final String english;
        public final String vietnamese;

        public Word(String english, String vietnamese) {
            this.english = english;
            this.vietnamese = vietnamese;
        }
    }

    /**
     * Built in beginner vocabulary. Parents can add more words in settings.
     */
    static final String[][] BUILT_IN_WORDS = {
            {"apple", "quả táo"}, {"banana", "quả chuối"}, {"orange", "quả cam"},
            {"cat", "con mèo"}, {"dog", "con chó"}, {"bird", "con chim"},
            {"fish", "con cá"}, {"chicken", "con gà"}, {"duck", "con vịt"},
            {"cow", "con bò"}, {"pig", "con lợn"}, {"elephant", "con voi"},
            {"tiger", "con hổ"}, {"monkey", "con khỉ"}, {"rabbit", "con thỏ"},
            {"red", "màu đỏ"}, {"blue", "màu xanh dương"}, {"green", "màu xanh lá"},
            {"yellow", "màu vàng"}, {"black", "màu đen"}, {"white", "màu trắng"},
            {"pink", "màu hồng"}, {"one", "số một"}, {"two", "số hai"},
            {"three", "số ba"}, {"four", "số bốn"}, {"five", "số năm"},
            {"six", "số sáu"}, {"seven", "số bảy"}, {"eight", "số tám"},
            {"nine", "số chín"}, {"ten", "số mười"}, {"mother", "mẹ"},
            {"father", "bố"}, {"brother", "anh/em trai"}, {"sister", "chị/em gái"},
            {"teacher", "cô giáo/thầy giáo"}, {"school", "trường học"}, {"book", "quyển sách"},
            {"pen", "cái bút"}, {"bag", "cái cặp"}, {"table", "cái bàn"},
            {"chair", "cái ghế"}, {"door", "cái cửa"}, {"window", "cửa sổ"},
            {"house", "ngôi nhà"}, {"car", "ô tô"}, {"bike", "xe đạp"},
            {"ball", "quả bóng"}, {"doll", "búp bê"}, {"sun", "mặt trời"},
            {"moon", "mặt trăng"}, {"star", "ngôi sao"}, {"rain", "mưa"},
            {"tree", "cái cây"}, {"flower", "bông hoa"}, {"water", "nước"},
            {"milk", "sữa"}, {"rice", "cơm"}, {"bread", "bánh mì"},
            {"egg", "quả trứng"}, {"hand", "bàn tay"}, {"eye", "con mắt"},
            {"nose", "cái mũi"}, {"mouth", "cái miệng"}, {"ear", "cái tai"},
            {"big", "to"}, {"small", "nhỏ"}, {"happy", "vui vẻ"},
            {"sad", "buồn"}, {"hot", "nóng"}, {"cold", "lạnh"},
    };

    private final Random random;

    public KidsQuiz() {
        this(new Random());
    }

    public KidsQuiz(Random random) {
        this.random = random;
    }

    /**
     * @param includeMath    Include math questions.
     * @param includeEnglish Include English vocabulary questions.
     *                       If both are false, math is used.
     * @param mathLevel      1: +/- within 10, 2: within 20, 3: within 100, 4: multiply/divide.
     * @param customVocab    Extra words, one per line as "english=vietnamese".
     */
    public Question next(boolean includeMath, boolean includeEnglish, int mathLevel, String customVocab) {
        final boolean english;
        if (includeMath && includeEnglish) {
            english = random.nextBoolean();
        } else {
            english = includeEnglish;
        }
        return english
                ? english(customVocab)
                : math(mathLevel);
    }

    // ---------------------------------------------------------------- Math

    public Question math(int level) {
        level = Math.max(MATH_LEVEL_MIN, Math.min(MATH_LEVEL_MAX, level));

        final int a, b, answer;
        final char op;
        switch (level) {
            case 1:
            case 2:
            case 3: {
                final int max = level == 1 ? 10 : (level == 2 ? 20 : 100);
                if (random.nextBoolean()) {
                    // Addition with a sum no larger than max.
                    answer = randomInRange(2, max);
                    a = randomInRange(1, answer - 1);
                    b = answer - a;
                    op = '+';
                } else {
                    // Subtraction with a non negative result.
                    a = randomInRange(2, max);
                    b = randomInRange(1, a);
                    answer = a - b;
                    op = '-';
                }
                break;
            }
            default: {
                final int x = randomInRange(2, 9);
                final int y = randomInRange(2, 9);
                if (random.nextBoolean()) {
                    a = x;
                    b = y;
                    answer = x * y;
                    op = '×';
                } else {
                    a = x * y;
                    b = x;
                    answer = y;
                    op = ':';
                }
            }
        }

        String prompt = a + " " + op + " " + b + " = ?";
        return buildNumberQuestion(prompt, answer);
    }

    private Question buildNumberQuestion(String prompt, int answer) {
        Set<Integer> values = new LinkedHashSet<>();
        values.add(answer);
        final int spread = Math.max(3, answer / 5);
        int attempts = 0;
        while (values.size() < OPTION_COUNT && attempts++ < 100) {
            int offset = randomInRange(1, spread) * (random.nextBoolean() ? 1 : -1);
            int candidate = answer + offset;
            if (candidate >= 0) values.add(candidate);
        }
        // Fallback for tiny answers where negative offsets were rejected.
        int filler = answer + spread + 1;
        while (values.size() < OPTION_COUNT) values.add(filler++);

        List<String> options = new ArrayList<>(OPTION_COUNT);
        for (Integer value : values) options.add(String.valueOf(value));
        return shuffle(prompt, options, String.valueOf(answer));
    }

    // ---------------------------------------------------------------- English

    public Question english(String customVocab) {
        List<Word> words = vocabulary(customVocab);
        Collections.shuffle(words, random);
        Word target = words.get(0);

        // Alternate between "what is X in English" and "what does X mean".
        final boolean askEnglish = random.nextBoolean();
        List<String> options = new ArrayList<>(OPTION_COUNT);
        Set<String> seen = new LinkedHashSet<>();
        for (Word word : words) {
            String option = askEnglish ? word.english : word.vietnamese;
            if (seen.add(option.toLowerCase(Locale.ROOT))) options.add(option);
            if (options.size() == OPTION_COUNT) break;
        }

        String correct = askEnglish ? target.english : target.vietnamese;
        String prompt = askEnglish
                ? "\"" + target.vietnamese + "\" trong tiếng Anh là gì?"
                : "\"" + target.english + "\" nghĩa là gì?";
        return shuffle(prompt, options, correct);
    }

    /**
     * Built in words plus valid custom words. Custom words override built in words with the same English text.
     */
    public static List<Word> vocabulary(String customVocab) {
        List<Word> custom = parseVocabulary(customVocab);
        Set<String> customEnglish = new LinkedHashSet<>();
        for (Word word : custom) customEnglish.add(word.english.toLowerCase(Locale.ROOT));

        List<Word> words = new ArrayList<>(custom);
        for (String[] pair : BUILT_IN_WORDS) {
            if (!customEnglish.contains(pair[0])) words.add(new Word(pair[0], pair[1]));
        }
        return words;
    }

    public static List<Word> parseVocabulary(String raw) {
        List<Word> words = new ArrayList<>();
        if (raw == null) return words;
        for (String line : raw.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String en = line.substring(0, separator).trim();
            String vi = line.substring(separator + 1).trim();
            if (!en.isEmpty() && !vi.isEmpty()) words.add(new Word(en, vi));
        }
        return words;
    }

    // ---------------------------------------------------------------- Helpers

    private Question shuffle(String prompt, List<String> options, String correct) {
        Collections.shuffle(options, random);
        return new Question(prompt, options.toArray(new String[0]), options.indexOf(correct));
    }

    private int randomInRange(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }
}
