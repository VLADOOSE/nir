package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoszakupDtoJsonTest {

    final ObjectMapper om = new ObjectMapper();

    @Test
    void parsesTrdBuyPage() throws Exception {
        String json = """
            {"total":171946,"next_page":"/v2/trd-buy?page=next&search_after=414621",
             "items":[{"id":414621,"number_anno":"415500-1","name_ru":"Аппарат УЗИ",
                       "total_sum":12000000.50,"count_lots":2,"ref_buy_status_id":230,
                       "customer_bin":"123456789012","org_bin":"987654321098",
                       "publish_date":"2026-06-01T00:00:00","start_date":"2026-06-02T00:00:00",
                       "end_date":"2026-06-20T00:00:00"}]}
            """;
        TrdBuyPageDto page = om.readValue(json, TrdBuyPageDto.class);
        assertThat(page.getNextPage()).contains("search_after=414621");
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getNumberAnno()).isEqualTo("415500-1");
        assertThat(page.getItems().get(0).getNameRu()).isEqualTo("Аппарат УЗИ");
        assertThat(page.getItems().get(0).getRefBuyStatusId()).isEqualTo(230);
        assertThat(page.getItems().get(0).getTotalSum()).isEqualByComparingTo("12000000.50");
    }

    @Test
    void parsesLot() throws Exception {
        String json = """
            {"lot_number":"1","name_ru":"Аппарат УЗИ портативный","amount":6000000,
             "count":2,"trd_buy_number_anno":"415500-1"}
            """;
        LotDto lot = om.readValue(json, LotDto.class);
        assertThat(lot.getLotNumber()).isEqualTo("1");
        assertThat(lot.getNameRu()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(lot.getCount()).isEqualTo(2);
    }
}
