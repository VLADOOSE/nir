package com.vladoose.nir.pricelist;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.supplierreply.HeaderRoles;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V44 (спека 2026-10-23-price-lists-and-rates §8): таблицы, JSONB цен, каскад, рынок, роль «РУ». */
@SpringBootTest
@Transactional
class PriceListSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DistributorRepository distributors;
    @Autowired PriceListRepository lists;
    @Autowired PriceListItemRepository items;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v44Tables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in "
                + "('currency_rate', 'official_rate', 'price_list', 'price_list_item', 'inbound_email_file')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'price_request_item' "
                + "and column_name in ('response_currency', 'response_original', 'response_rate')", String.class)).hasSize(3);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'client_offer_item' "
                + "and column_name in ('purchase_currency', 'purchase_original', 'purchase_rate', 'price_list_item_id')", String.class)).hasSize(4);
    }

    @Test
    void pricesRoundTrip_withEmptyCell() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Аксион");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.DRAFT, "RUB", LocalDate.of(2026, 9, 1),
                new Object[]{"Дефибриллятор ДКИ-Н-12", "150000", "дкин12"});
        p.getColumns().add(new PriceListColumn(1, "Оптовая"));
        p.getItems().get(0).getPrices().add(null);
        lists.saveAndFlush(p);
        em.clear();
        PriceList back = lists.findById(p.getId()).orElseThrow();
        assertThat(back.getColumns()).extracting(PriceListColumn::header).containsExactly("Цена", "Оптовая");
        assertThat(back.getItems().get(0).getPrices()).hasSize(2);
        assertThat(back.getItems().get(0).getPrices().get(0)).isEqualByComparingTo("150000");
        assertThat(back.getItems().get(0).getPrices().get(1)).isNull();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
    }

    @Test
    void deleteList_cascadesItems_offerLinkSetNull() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Каскад");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.ACTIVE, "RUB", null, new Object[]{"Строка", "10"});
        Long itemId = p.getItems().get(0).getId();
        Long offerId = jdbc.queryForObject("insert into client_offer (market, number, offer_date, title, table_columns, status, version, origin) "
                + "values ('KZ', null, current_date, 'ZZ', '[]', 'DRAFT', 0, 'BUILDER') returning id", Long.class);
        jdbc.update("insert into client_offer_item (offer_id, line_no, kind, name, unit, price_list_item_id) values (?, 1, 'ITEM', 'x', 'шт', ?)",
                offerId, itemId);
        lists.delete(p);
        lists.flush();
        assertThat(jdbc.queryForObject("select count(*) from price_list_item where id = ?", Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("select price_list_item_id from client_offer_item where offer_id = ?", Long.class, offerId)).isNull();
    }

    @Test
    void sameFileOfSupplier_twice_isRejected() {
        Distributor d = PriceListFixtures.supplier(distributors, Market.KZ, "Дубль");
        PriceList p = PriceListFixtures.list(lists, d, PriceListStatus.DRAFT, "RUB", null);
        assertThat(lists.findByDistributorIdAndSha256(d.getId(), p.getSha256())).isPresent();
        PriceList twin = PriceList.builder().market(Market.KZ).distributor(d).fileName("x").sha256(p.getSha256())
                .kind(ReplyFileKind.EXCEL).parseStatus(ReplyParseStatus.PARSED).build();
        assertThatThrownBy(() -> lists.saveAndFlush(twin)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marketFilter_hidesOtherMarket() {
        Distributor kz = PriceListFixtures.supplier(distributors, Market.KZ, "KZ");
        PriceList p = PriceListFixtures.list(lists, kz, PriceListStatus.ACTIVE, "RUB", null);
        MarketContext.set(Market.RF);
        assertThat(lists.findAll()).extracting(PriceList::getId).doesNotContain(p.getId());
        MarketContext.set(Market.KZ);
        assertThat(lists.findAll()).extracting(PriceList::getId).contains(p.getId());
    }

    @Test
    void regRole() {
        Map<String, LineField> none = Map.of();
        assertThat(HeaderRoles.role("Разрешительные документы в Казахстане", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Регистрационное удостоверение (номер, срок действия)", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("№ РУ", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Регистрация в РК", none)).isEqualTo(LineField.REG);
        assertThat(HeaderRoles.role("Цена", none)).isEqualTo(LineField.PRICE);
        assertThat(HeaderRoles.role("№ п/п", none)).isEqualTo(LineField.NUMBER);
        assertThat(jdbc.queryForObject("select field from header_synonym where header_norm = 'регистрационное удостоверение'",
                String.class)).isEqualTo("REG");
    }

    @Test
    void currencyRateUnique() {
        jdbc.update("insert into currency_rate (market, currency, our_rate) values ('KZ', 'ZZZ', 6)");
        assertThat(jdbc.queryForObject("select our_rate from currency_rate where market = 'KZ' and currency = 'ZZZ'", BigDecimal.class))
                .isEqualByComparingTo("6");
        // после отказа PostgreSQL прерывает транзакцию теста — проверка значения выше, до него
        assertThatThrownBy(() -> jdbc.update("insert into currency_rate (market, currency, our_rate) values ('KZ', 'ZZZ', 7)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
