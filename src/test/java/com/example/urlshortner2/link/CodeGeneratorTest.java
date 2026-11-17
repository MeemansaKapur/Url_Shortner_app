package com.example.urlshortner2.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    @Test
    void producesCodesOfRequestedLengthFromBase62Alphabet() {
        for (int i = 0; i < 1_000; i++) {
            String c = CodeGenerator.newCode(7);
            assertThat(c).hasSize(7).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void codesAreNotRepeatedInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            seen.add(CodeGenerator.newCode(7));
        }
        assertThat(seen).hasSize(100_000);
    }

    @Test
    void characterDistributionIsRoughlyUniform() {
        Map<Character, Integer> counts = new HashMap<>();
        int n = 620_000;
        for (int i = 0; i < n / 7; i++) {
            for (char ch : CodeGenerator.newCode(7).toCharArray()) {
                counts.merge(ch, 1, Integer::sum);
            }
        }
        assertThat(counts).hasSize(62);
        double expected = (n / 7 * 7) / 62.0;
        counts.values().forEach(v -> assertThat((double) v).isBetween(expected * 0.9, expected * 1.1));
    }

    @Test
    void validatesCodeShape() {
        assertThat(CodeGenerator.isValidCode("aB3xK9q")).isTrue();
        assertThat(CodeGenerator.isValidCode("favicon.ico")).isFalse();
        assertThat(CodeGenerator.isValidCode("")).isFalse();
        assertThat(CodeGenerator.isValidCode("a".repeat(17))).isFalse();
    }
}
