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
import java.util.Random;
import java.util.Set;

/**
 * Generates simple multiple choice math questions.
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

    private final Random random;

    public KidsQuiz() {
        this(new Random());
    }

    public KidsQuiz(Random random) {
        this.random = random;
    }

    /**
     * @param mathLevel 1: +/- within 10, 2: within 20, 3: within 100, 4: multiply/divide.
     */
    public Question next(int mathLevel) {
        return math(mathLevel);
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
