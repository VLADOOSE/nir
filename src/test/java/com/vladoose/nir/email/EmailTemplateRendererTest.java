package com.vladoose.nir.email;

import com.vladoose.nir.service.EmailTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void substitutesKnownKeys() {
        String out = renderer.render("Привет, {{имя}}! {{город}}",
                Map.of("имя", "Иван", "город", "Алматы"));
        assertThat(out).isEqualTo("Привет, Иван! Алматы");
    }

    @Test
    void leavesUnknownPlaceholderAsIs() {
        String out = renderer.render("A {{известно}} B {{неизвестно}}",
                Map.of("известно", "X"));
        assertThat(out).isEqualTo("A X B {{неизвестно}}");
    }

    @Test
    void multilineValuePreserved() {
        String out = renderer.render("Список:\n{{позиции}}конец",
                Map.of("позиции", "— a\n— b\n"));
        assertThat(out).isEqualTo("Список:\n— a\n— b\nконец");
    }

    @Test
    void nullTemplateIsEmpty() {
        assertThat(renderer.render(null, Map.of())).isEqualTo("");
    }
}
