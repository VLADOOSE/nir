package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LotTechSpecParseTest {

    private final ObjectMapper om = new ObjectMapper();

    /** Живой формат v3 Lots.Files (снят токеном 2026-07-04, тендер 17280874-1). */
    private static final String LIVE = """
        {"data":{"Lots":[
          {"lotNumber":"87273742-ОИ2","nameRu":"Пульсоксиметр","Files":[
            {"nameRu":"Техническая спецификация",
             "filePath":"https://ows.goszakup.gov.kz/download/trd_buy_lots_list/a8174cac",
             "originalName":"techspec_17280874_.pdf"}]}
        ]}}""";

    private static final String TWO_SAME_NAME = """
        {"data":{"Lots":[
          {"nameRu":"Сумка","Files":[{"nameRu":"Техническая спецификация","filePath":"u1","originalName":"f1.pdf"}]},
          {"nameRu":"Сумка","Files":[{"nameRu":"Техническая спецификация","filePath":"u2","originalName":"f2.pdf"}]},
          {"nameRu":"Другое","Files":[]}
        ]}}""";

    @Test
    void findsTechSpecByLotName() throws Exception {
        JsonNode root = om.readTree(LIVE);
        LotTechSpecRef ref = GoszakupHttpClient.parseLotTechSpec(root, "Пульсоксиметр");
        assertThat(ref).isNotNull();
        assertThat(ref.filePath()).contains("/download/trd_buy_lots_list/");
        assertThat(ref.originalName()).isEqualTo("techspec_17280874_.pdf");
        assertThat(ref.ambiguous()).isFalse();
    }

    @Test
    void nameMatchIsCaseInsensitiveAndTrimmed() throws Exception {
        JsonNode root = om.readTree(LIVE);
        assertThat(GoszakupHttpClient.parseLotTechSpec(root, "  пульсоксиметр ")).isNotNull();
    }

    @Test
    void duplicateNamesReturnFirstWithAmbiguousFlag() throws Exception {
        JsonNode root = om.readTree(TWO_SAME_NAME);
        LotTechSpecRef ref = GoszakupHttpClient.parseLotTechSpec(root, "Сумка");
        assertThat(ref).isNotNull();
        assertThat(ref.filePath()).isEqualTo("u1");
        assertThat(ref.ambiguous()).isTrue();
    }

    @Test
    void unknownLotOrNoTechSpecFileGivesNull() throws Exception {
        JsonNode root = om.readTree(LIVE);
        assertThat(GoszakupHttpClient.parseLotTechSpec(root, "Томограф")).isNull();
        assertThat(GoszakupHttpClient.parseLotTechSpec(om.readTree(TWO_SAME_NAME), "Другое")).isNull();
    }
}
