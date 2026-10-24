package com.insightgiver.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void leavesTextThatFitsAlone() {
        assertThat(Text.clip("short", 10)).isEqualTo("short");
    }

    @Test
    void cutsAtTheLastWordBreak() {
        assertThat(Text.clip("the quick brown fox jumps", 17)).isEqualTo("the quick brown");
    }

    @Test
    void cutsMidWordWhenThereIsNoNearbyBreak() {
        assertThat(Text.clip("오늘은정말긴하루였다그래도괜찮아", 6)).isEqualTo("오늘은정말긴");
    }

    @Test
    void neverSplitsASurrogatePair() {
        String text = "ab😀cd"; // "ab😀cd"
        assertThat(Text.clip(text, 3)).isEqualTo("ab");
    }
}
