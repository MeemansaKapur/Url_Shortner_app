package com.example.urlshortner2.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "HTTPS://Example.com/x",
    })
    void acceptsHttpAndHttps(String url) {
        assertThatCode(() -> UrlValidator.validate(url, "sho.rt")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "example.com",
            "https://",
            "https://exa mple.com",
            "https://sho.rt/abc1234",
            "https://SHO.RT/abc1234",
    })
    void rejectsUnsafeOrInvalidUrls(String url) {
        assertThatThrownBy(() -> UrlValidator.validate(url, "sho.rt")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {2049, 5000})
    void rejectsTooLongUrls(int length) {
        String url = "https://example.com/" + "a".repeat(length - 20);
        assertThatThrownBy(() -> UrlValidator.validate(url, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
