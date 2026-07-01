package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KatoDictionaryTest {

    FakeGoszakupClient fake;
    KatoDictionary dict;

    @BeforeEach
    void setUp() {
        fake = new FakeGoszakupClient();
        // справочник в 2 страницы: ЗКО (27) и Карагандинская (35) вперемешку
        fake.katoPage(null, "/v2/refs/ref_kato?page=next&search_after=1",
                FakeGoszakupClient.kato("27", "10", "10", "000"),
                FakeGoszakupClient.kato("35", "28", "10", "000"));
        fake.katoPage("/v2/refs/ref_kato?page=next&search_after=1", null,
                FakeGoszakupClient.kato("27", "44", "30", "300"),
                FakeGoszakupClient.kato("71", "10", "00", "000"));
        dict = new KatoDictionary(fake);
    }

    @Test
    void collectsAllCodesOfRegion_acrossPages() {
        List<String> zko = dict.codesForRegion("Западно-Казахстанская область");
        assertThat(zko).containsExactlyInAnyOrder("271010000", "274430300");
    }

    @Test
    void cachesDictionary_secondCallDoesNotRefetch() {
        dict.codesForRegion("Западно-Казахстанская область");
        int fetchesAfterFirst = fake.katoFetches;
        dict.codesForRegion("г. Астана");
        assertThat(fake.katoFetches).isEqualTo(fetchesAfterFirst);
    }

    @Test
    void unknownRegion_returnsEmpty() {
        assertThat(dict.codesForRegion("Регион не указан")).isEmpty();
        assertThat(dict.codesForRegion(null)).isEmpty();
        assertThat(fake.katoFetches).isZero(); // без известного префикса справочник не тянем
    }
}
