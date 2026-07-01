package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
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
    void effectiveBin_fallsBackToOrgBin_liveTrdBuyShape() throws Exception {
        // живой /v2/trd-buy НЕ отдаёт customer_bin — только org_bin
        String live = """
            {"id":17276577,"number_anno":"17276577-1","name_ru":"Приобретение краски",
             "org_bin":"971240005114","ref_buy_status_id":210,"system_id":3,
             "publish_date":"2026-07-01 23:39:51","total_sum":100000}
            """;
        TrdBuyDto d = om.readValue(live, TrdBuyDto.class);
        assertThat(d.getCustomerBin()).isNull();
        assertThat(d.effectiveBin()).isEqualTo("971240005114");
    }

    @Test
    void effectiveBin_prefersCustomerBin_whenBothPresent() {
        TrdBuyDto d = new TrdBuyDto();
        d.setCustomerBin("111111111111");
        d.setOrgBin("222222222222");
        assertThat(d.effectiveBin()).isEqualTo("111111111111");
    }

    @Test
    void parsesSubject_liveShape_addressArray() throws Exception {
        // живой /v2/subject/biin/{биин}: address — МАССИВ объектов, КАТО и адрес внутри элементов
        String live = """
            {"pid":2787,"bin":"971240005114","name_ru":"КГУ \\"Школа-гимназия имени Шокана Уалиханова\\"",
             "address":[{"id":"1854","pid":"2787","country_code":"398","kato_code":"352810000",
                         "address_type":"1","address":"Карагандинская область, г.Шахтинск, ул. Парковая, дом 23/1",
                         "phone":"872-156-39336"}]}
            """;
        com.vladoose.nir.integration.goszakup.dto.SubjectDto s =
                om.readValue(live, com.vladoose.nir.integration.goszakup.dto.SubjectDto.class);
        assertThat(s.getNameRu()).startsWith("КГУ");
        assertThat(s.firstAddress()).startsWith("Карагандинская область");
        assertThat(s.firstKato()).isEqualTo("352810000");
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

    @Test
    void parsesLot_liveShape_descriptionRuIsSpec() throws Exception {
        // живой /v2/lots/number-anno/{anno}: description_ru — техспека лота, lot_number со суффиксом
        String live = """
            {"lot_number":"87197521-ОИ2","name_ru":"Работы по ремонту/модернизации медицинского оборудования",
             "description_ru":"Работы по ремонту/модернизации медицинского/санитарного/терапевтического оборудования",
             "count":1,"amount":540000,"ref_lot_status_id":220,"trd_buy_number_anno":"17276387-1"}
            """;
        LotDto lot = om.readValue(live, LotDto.class);
        assertThat(lot.getDescriptionRu()).startsWith("Работы по ремонту/модернизации медицинского/санитарного");
    }
}
