package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.westmed.dto.WestmedPriceRequest;
import com.vladoose.nir.integration.westmed.dto.WestmedProduct;
import com.vladoose.nir.integration.westmed.dto.WestmedQuoteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WestmedLeadMapperTest {

    FakeWestmedClient fake;
    WestmedProductLookup lookup;
    final WestmedLeadMapper mapper = new WestmedLeadMapper("https://westmed.kz/");

    @BeforeEach
    void setUp() {
        fake = new FakeWestmedClient();
        lookup = new WestmedProductLookup(fake);
    }

    private static WestmedPriceRequest price(String productName, String status) {
        return new WestmedPriceRequest("u1", "Айгерим", "a@clinic.kz", "+77770000000", null,
                "Сколько стоит?", productName, status, "2026-09-20T08:15:30.123456Z");
    }

    @Test
    void priceRequestForProductGetsBrandAndLinkFromCatalog() {
        fake.productsBySearch.put("стерилизатор озоновый «орион»", List.of(
                new WestmedProduct("Стерилизатор озоновый «Орион» без камеры", "orion-bk", "Орион"),
                new WestmedProduct("Стерилизатор озоновый «Орион»", "orion", "Орион")));

        IncomingLead in = mapper.fromPriceRequest(price("Стерилизатор озоновый «Орион»", "NEW"), lookup);

        assertThat(in.source()).isEqualTo("westmed.kz");
        assertThat(in.externalId()).isEqualTo("price:u1");
        assertThat(in.channel()).isEqualTo(LeadChannel.SITE);
        assertThat(in.subject()).isEqualTo("Запрос цены");
        assertThat(in.items()).containsExactly(new IncomingLead.Item(
                "Стерилизатор озоновый «Орион»", "Орион", 1, "https://westmed.kz/product/orion"));
        assertThat(in.initialStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(in.extStatus()).isEqualTo("NEW");
        assertThat(in.receivedAt()).isEqualTo(OffsetDateTime.parse("2026-09-20T08:15:30.123456Z"));
        assertThat(in.author()).isNull();
    }

    @Test
    void generalRequestWithoutProductHasNoItems() {
        IncomingLead in = mapper.fromPriceRequest(price(null, "NEW"), lookup);

        assertThat(in.subject()).isEqualTo("Заявка с сайта");
        assertThat(in.items()).isEmpty();
        assertThat(in.message()).isEqualTo("Сколько стоит?");
        assertThat(fake.searchCalls).isZero();
    }

    @Test
    void quoteItemsGetBrandBySlugAndLinkEvenWhenGoneFromCatalog() {
        fake.productsBySearch.put("облучатель обн-150", List.of(new WestmedProduct("Облучатель ОБН-150", "obn-150", "Азов")));
        WestmedQuoteRequest q = new WestmedQuoteRequest("q1", "Иван", "i@x.kz", null, "ТОО «Клиника»", null, "NEW",
                List.of(new WestmedQuoteRequest.Item("obn-150", "Облучатель ОБН-150", 3),
                        new WestmedQuoteRequest.Item("gone", "Снятый с сайта товар", null)),
                "2026-09-20T08:15:30Z");

        IncomingLead in = mapper.fromQuoteRequest(q, lookup);

        assertThat(in.externalId()).isEqualTo("quote:q1");
        assertThat(in.subject()).isEqualTo("Запрос КП");
        assertThat(in.items()).containsExactly(
                new IncomingLead.Item("Облучатель ОБН-150", "Азов", 3, "https://westmed.kz/product/obn-150"),
                new IncomingLead.Item("Снятый с сайта товар", null, 1, "https://westmed.kz/product/gone"));
    }

    @Test
    void siteStatusMapsToInitialStatus() {
        assertThat(WestmedLeadMapper.initialStatus("NEW")).isEqualTo(LeadStatus.NEW);
        assertThat(WestmedLeadMapper.initialStatus("PROCESSED")).isEqualTo(LeadStatus.IN_WORK);
        assertThat(WestmedLeadMapper.initialStatus("CLOSED")).isEqualTo(LeadStatus.CLOSED);
        assertThat(WestmedLeadMapper.initialStatus(null)).isEqualTo(LeadStatus.NEW);
        assertThat(WestmedLeadMapper.initialStatus("WHATEVER")).isEqualTo(LeadStatus.NEW);
    }

    @Test
    void catalogIsSearchedOncePerNamePerCycle() {
        mapper.fromPriceRequest(price("Облучатель ОБН-150", "NEW"), lookup);
        mapper.fromPriceRequest(price("облучатель  обн-150 ", "NEW"), lookup);

        assertThat(fake.searchCalls).isEqualTo(1);
    }

    @Test
    void catalogFailureLeavesItemWithoutBrand() {
        fake.failSearchWith = new WestmedApiException(0, "сайт недоступен: ConnectException");

        IncomingLead in = mapper.fromPriceRequest(price("Облучатель ОБН-150", "NEW"), lookup);

        assertThat(in.items()).containsExactly(new IncomingLead.Item("Облучатель ОБН-150", null, 1, null));
    }

    @Test
    void brokenTimestampFallsBackToNull() {
        assertThat(WestmedLeadMapper.parseTime("вчера")).isNull();
        assertThat(WestmedLeadMapper.parseTime(null)).isNull();
    }
}
