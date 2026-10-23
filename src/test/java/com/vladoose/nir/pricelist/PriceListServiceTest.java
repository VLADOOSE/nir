package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PriceListPatch;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceListRepository;
import com.vladoose.nir.service.pricelist.PriceListService;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PriceListServiceTest {

    @Autowired PriceListService service;
    @Autowired PriceListRepository lists;
    @Autowired DistributorRepository distributors;
    Distributor axion;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        axion = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион");
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void upload_draftRows_ourColumnPrice() throws Exception {
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        PriceList p = lists.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PriceListStatus.DRAFT);
        assertThat(p.getItems()).hasSize(4);
        assertThat(p.getOurColumn()).isEqualTo(1);
        assertThat(p.getItems().get(1).getOurPrice()).isEqualByComparingTo("150000");
        assertThat(p.getItems().get(1).getModelKey()).isEqualTo("дкин12");
        assertThat(p.getItems().get(1).getRegNumber()).isEqualTo("РК МИ (МТ)-0№024573");
        assertThat(p.getItems().get(3).isNoReg()).isTrue();
        assertThat(p.getCreatedBy()).isEqualTo("vlad");
        assertThat(p.getMarket()).isEqualTo(Market.KZ);
    }

    /** Review Focus 4: тот же файл того же поставщика — прежний прайс; поставщик другого рынка — 400. */
    @Test
    void sameFile_returnsExisting_otherMarketSupplier_400() throws Exception {
        Long a = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        Long b = service.upload(axion.getId(), "копия.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        assertThat(b).isEqualTo(a);
        Distributor rf = PriceListFixtures.supplier(distributors, Market.RF, "РФ");
        assertThatThrownBy(() -> service.upload(rf.getId(), "x.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Поставщик не найден");
    }

    /** Review Focus 5: скан — черновик без строк с пометкой; файл сохранён. */
    @Test
    void scan_draftWithoutRows() throws Exception {
        Long id = service.upload(axion.getId(), "скан.pdf", "application/pdf", ReplyFixtures.pdfBytes(1), "vlad");
        PriceList p = lists.findById(id).orElseThrow();
        assertThat(p.getItems()).isEmpty();
        assertThat(p.getParseNote()).startsWith("Строки не найдены");
        assertThat(p.getData()).isNotEmpty();
    }

    @Test
    void patch_columnDiscountCurrencyDates_rows() throws Exception {
        Long id = service.upload(axion.getId(), "ZZ прайс.xlsx", null, PriceListFiles.axionLikeXlsx(), "vlad");
        service.patch(id, new PriceListPatch("OUR_COLUMN", "0"), "vlad");
        assertThat(lists.findById(id).orElseThrow().getItems().get(1).getOurPrice()).isEqualByComparingTo("205000");
        service.patch(id, new PriceListPatch("DISCOUNT_PCT", "10"), "vlad");
        assertThat(lists.findById(id).orElseThrow().getItems().get(1).getOurPrice()).isEqualByComparingTo("184500");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("CURRENCY", "GBP"), "vlad")).hasMessage("Валюта — KZT, RUB, USD, EUR или CNY");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("DISCOUNT_PCT", "100"), "vlad")).hasMessage("Скидка — от 0 до 99,99 %");
        assertThatThrownBy(() -> service.patch(id, new PriceListPatch("VALID_TO", "2026-01-01"), "vlad"))
                .hasMessage("«Действует до» раньше даты прайса");
        Long rowId = lists.findById(id).orElseThrow().getItems().get(0).getId();
        service.patchRow(id, rowId, new PriceListPatch("PRICE:1", "250 000"));
        PriceListItem row = lists.findById(id).orElseThrow().getItems().get(0);
        assertThat(row.getPrices().get(1)).isEqualByComparingTo("250000");
        service.patchRow(id, rowId, new PriceListPatch("MODEL", "ВМ-03"));
        assertThat(lists.findById(id).orElseThrow().getItems().get(0).getModelKey()).isEqualTo("вм03");
        assertThatThrownBy(() -> service.patchRow(id, rowId, new PriceListPatch("NAME", " "))).hasMessage("Наименование не может быть пустым");
        service.activate(id, "vlad");
        assertThat(lists.findById(id).orElseThrow().getStatus()).isEqualTo(PriceListStatus.ACTIVE);
        service.delete(id);
        assertThat(lists.findById(id)).isEmpty();
    }
}
