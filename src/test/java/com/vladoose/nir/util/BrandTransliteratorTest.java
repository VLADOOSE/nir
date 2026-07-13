package com.vladoose.nir.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrandTransliteratorTest {

    @Test
    void expandsLatinBrandToCyrillic() {
        var out = BrandTransliterator.expand("Samsung");
        assertThat(out).first().isEqualTo("Samsung");   // оригинал первым
        assertThat(out).contains("Самсунг");
    }

    @Test
    void expandsCyrillicBrandToLatin() {
        // pg_trgm регистронезависим — латинский вариант служебный (не показывается), регистр не важен
        assertThat(BrandTransliterator.expand("Самсунг")).anyMatch(s -> s.equalsIgnoreCase("Samsung"));
    }

    @Test
    void unknownBrand_returnsSingletonOriginal() {
        assertThat(BrandTransliterator.expand("Элэскулап")).containsExactly("Элэскулап");
    }

    @Test
    void blankOrNull_returnsEmpty() {
        assertThat(BrandTransliterator.expand(null)).isEmpty();
        assertThat(BrandTransliterator.expand("  ")).isEmpty();
    }
}
