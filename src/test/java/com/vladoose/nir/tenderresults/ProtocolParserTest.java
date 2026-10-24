package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.FailReason;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import com.vladoose.nir.service.tenderresults.protocol.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Протоколы итогов (спека 2026-10-22-tender-results-market-prices §5) — на обезличенных настоящих протоколах. */
class ProtocolParserTest {

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = ProtocolParserTest.class.getResourceAsStream("/goszakup/protocols/" + name)) {
            return in.readAllBytes();
        }
    }

    /** Текст PDF — как его отдаёт PDFBox (фикстура — уже текст: обезличить PDF нельзя). */
    private static ProtocolResult text(String name) throws IOException {
        return ProtocolParser.parseLines(ProtocolText.textLines(new String(bytes(name), StandardCharsets.UTF_8)));
    }

    @Test
    void zcp_winner_tenBids_sortedByPrice() throws IOException {
        ProtocolResult r = text("zcp-winner.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.ZCP);
        LotProtocol l = r.lots().get("88214663-ЗЦП2");
        assertThat(l.bidsCount()).isEqualTo(10);
        assertThat(l.rejectedCount()).isZero();
        assertThat(l.bids()).hasSize(10);
        ProtocolBid first = l.bids().get(0);
        assertThat(first.place()).isEqualTo(1);
        assertThat(first.name()).isEqualTo("Поставщик 1");
        assertThat(first.bin()).isEqualTo("900000000001");
        assertThat(first.unitPrice()).isEqualByComparingTo("251000");
        assertThat(first.total()).isEqualByComparingTo("753000");
        assertThat(first.submittedAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 17, 42, 30));
        assertThat(l.bids().get(9).unitPrice()).isEqualByComparingTo("613500");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.secondBin()).isEqualTo("900000000002");
        assertThat(l.failReason()).isNull();
    }

    @Test
    void zcp_noBids() throws IOException {
        LotProtocol l = text("zcp-no-bids.txt").lots().get("88208881-ЗЦП1");
        assertThat(l.bidsCount()).isZero();
        assertThat(l.bids()).isEmpty();
        assertThat(l.failReason()).isEqualTo(FailReason.NO_BIDS);
    }

    @Test
    void zcp_oneBid() throws IOException {
        LotProtocol l = text("zcp-one-bid.txt").lots().get("88111123-ЗЦП2");
        assertThat(l.bidsCount()).isEqualTo(1);
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("3700000");
        assertThat(l.failReason()).isEqualTo(FailReason.ONE_BID);
        assertThat(l.winnerBin()).isNull();
    }

    @Test
    void oi_failed_supplierRefused() throws IOException {
        ProtocolResult r = text("oi-failed.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        LotProtocol l = r.lots().get("88208881-ОИ3");
        assertThat(l.bids()).extracting(ProtocolBid::bin).containsExactly("900000000001");
        assertThat(l.bids().get(0).total()).isNull();
        assertThat(l.bidsCount()).isZero();
        assertThat(l.failReason()).isEqualTo(FailReason.REFUSED);
    }

    @Test
    void oi_done_html() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("oi-done.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        LotProtocol l = r.lots().get("88138321-ОИ2");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("43000");
        assertThat(l.bidsCount()).isEqualTo(1);
        assertThat(l.failReason()).isNull();
    }

    /**
     * Протокол из одного источника на несколько лотов (17637016-ИОИ1, обезличено): у каждого лота — свой раздел «№ лота: …»
     * со своим поставщиком, ценой и решением. До 2026-10-23 разбирался только первый раздел — у остальных лотов «лота нет в протоколе».
     */
    @Test
    void oi_multiLot_html_eachLotFromItsSection() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("oi-multi-lot.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        assertThat(r.lots()).containsOnlyKeys("87906538-ОИ2", "87906588-ОИ2");
        LotProtocol second = r.lots().get("87906588-ОИ2");
        assertThat(second.winnerBin()).isEqualTo("900000000001");
        assertThat(second.bids()).extracting(ProtocolBid::name).containsExactly("ТОО \"Поставщик 1\"");
        assertThat(second.bids().get(0).unitPrice()).isEqualByComparingTo("23300");
        assertThat(second.bids().get(0).submittedAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 12, 35, 55));
        assertThat(second.conclusion()).startsWith("Решено заключить договор");
    }

    /**
     * Цена в протоколе ОИ: первая колонка («Выделенная сумма») — сумма поставщика, вторая («Цена поставщика») — цена за единицу.
     * Сверено с договорами площадки 2026-10-23: лот на 2 шт — «95238.08 | 47619.04», в договоре 47 619,04 за шт.
     */
    @Test
    void oi_priceRow_totalThenUnitPrice() throws IOException {
        ProtocolBid bid = ProtocolParser.parse(bytes("oi-multi-lot.html")).lots().get("87906538-ОИ2").bids().get(0);
        assertThat(bid.unitPrice()).isEqualByComparingTo("47619.04");
        assertThat(bid.total()).isEqualByComparingTo("95238.08");
    }

    /** PDF того же вида (17821999-ИОИ1, обезличено): семь лотов, один не состоялся — каждый со своим итогом. */
    @Test
    void oi_multiLot_pdf_sevenLots_oneFailed() throws IOException {
        ProtocolResult r = text("oi-multi-lot.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.OI);
        assertThat(r.lots()).containsOnlyKeys("84956222-ОИ2", "84954657-ОИ2", "84954982-ОИ2", "84955582-ОИ2", "84956014-ОИ2",
                "84956065-ОИ2", "84956100-ОИ2");
        LotProtocol failed = r.lots().get("84956065-ОИ2");
        assertThat(failed.winnerBin()).isNull();
        assertThat(failed.bidsCount()).isZero();
        assertThat(failed.bids()).extracting(ProtocolBid::bin).containsExactly("900000000001");
        assertThat(failed.failReason()).isEqualTo(FailReason.OTHER);
        LotProtocol twoPcs = r.lots().get("84954657-ОИ2");
        assertThat(twoPcs.winnerBin()).isEqualTo("900000000001");
        assertThat(twoPcs.bids().get(0).unitPrice()).isEqualByComparingTo("40200");
        assertThat(twoPcs.bids().get(0).total()).isEqualByComparingTo("80400");
        assertThat(r.lots().get("84956100-ОИ2").bids().get(0).unitPrice()).isEqualByComparingTo("2250");
    }

    /** PDF: название поставщика перенесено на строки перед БИН, вывод — на следующую строку; аббревиатуры в вывод не попадают. */
    @Test
    void oi_pdf_wrappedSupplierNameAndConclusion() throws IOException {
        ProtocolResult r = text("oi-multi-lot.txt");
        LotProtocol first = r.lots().get("84956222-ОИ2");
        assertThat(first.bids()).extracting(ProtocolBid::name)
                .containsExactly("Товарищество с ограниченной ответственностью \"Поставщик 1\"");
        assertThat(first.conclusion()).endsWith("с ограниченной ответственностью \"Поставщик 1\".");
        assertThat(r.lots().get("84956065-ОИ2").conclusion()).contains("не предоставил в установленный срок ответ на участие");
        assertThat(r.lots().get("84956100-ОИ2").conclusion()).endsWith("\"Поставщик 1\".").doesNotContain("Расшифровка");
    }

    /** Причина провала — по выводу целиком: «отказался» бывает на перенесённой строке. */
    @Test
    void oi_failReasonFromWrappedConclusion() {
        LotProtocol l = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом из одного источника по несостоявшимся закупкам №1-ИОИ1",
                "№ лота: 5-ОИ2",
                "Закупка способом из одного источника не состоялась в связи с тем, что приглашенный потенциальный поставщик Товарищество",
                "с ограниченной ответственностью \"Поставщик 1\" отказался от участия в закупке способом из одного источника.")).lots().get("5-ОИ2");
        assertThat(l.failReason()).isEqualTo(FailReason.REFUSED);
    }

    @Test
    void konkurs_winnersAndFailedLots_html() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("konkurs-winners.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.KONKURS);
        assertThat(r.lots()).hasSize(8);
        LotProtocol ekg = r.lots().get("87830010-ОК1");
        assertThat(ekg.bidsCount()).isEqualTo(2);
        assertThat(ekg.bids()).extracting(ProtocolBid::bin).containsExactly("900000000002", "900000000001");
        assertThat(ekg.bids().get(0).total()).isEqualByComparingTo("8745000");
        assertThat(ekg.bids().get(0).unitPrice()).isEqualByComparingTo("8745000");
        assertThat(ekg.winnerBin()).isEqualTo("900000000002");
        assertThat(ekg.secondBin()).isEqualTo("900000000001");
        assertThat(ekg.failReason()).isNull();
        LotProtocol cable = r.lots().get("87696595-ОК1");
        assertThat(cable.bidsCount()).isZero();
        assertThat(cable.failReason()).isEqualTo(FailReason.NO_BIDS);
        assertThat(cable.winnerBin()).isNull();
    }

    @Test
    void konkurs_noBids_html() throws IOException {
        LotProtocol l = ProtocolParser.parse(bytes("konkurs-no-bids.html")).lots().get("80997074-ОК3");
        assertThat(l.failReason()).isEqualTo(FailReason.NO_BIDS);
    }

    /**
     * «Цена поставщика» конкурса — за единицу (сверено 2026-10-24 с договорами площадки: 46 лотов больше 1 шт., у всех цена договора
     * за единицу без НДС = цене протокола); сумма заявки — цена × количество из перечня лотов, количества нет — суммы нет.
     */
    @Test
    void konkurs_supplierPricePerUnit_totalByQuantity() {
        ProtocolResult r = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "1 | 5-ОК1 | Насос | 2 | 100 | 200",
                "№ лота 5-ОК1",
                "Информация о представленных заявках на участие в конкурсе (лоте): 1",
                "1 | Поставщик 1 | 900000000001 | 01.10.2026 10:00:00.000",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 90 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00",
                "1. Определить победителем по лоту №5-ОК1:",
                "1 | Поставщик 1 | 900000000001 | Владелец"));
        LotProtocol l = r.lots().get("5-ОК1");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("90");
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("180");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        LotProtocol noQty = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "№ лота 6-ОК1",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 90 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00")).lots().get("6-ОК1");
        assertThat(noQty.bids().get(0).unitPrice()).as("цена за единицу — из протокола и без количества").isEqualByComparingTo("90");
        assertThat(noQty.bids().get(0).total()).as("количества нет — суммы нет").isNull();
    }

    /**
     * Одна заявка (живой протокол 17604518-1, насос, 2 шт.): строки «Определить победителем» нет — победитель тот, с кем «заключить
     * договор о государственных закупках с …»; цена — за единицу (6 700 000 ₸, как в договоре площадки), сумма — на 2 шт.
     */
    @Test
    void konkurs_singleBid_unitPrice_winnerFromContractLine() throws IOException {
        ProtocolResult r = ProtocolParser.parse(bytes("konkurs-single-bid.html"));
        assertThat(r.format()).isEqualTo(ProtocolFormat.KONKURS);
        LotProtocol pump = r.lots().get("87829972-ОК1");
        assertThat(pump.bids()).hasSize(1);
        assertThat(pump.bids().get(0).unitPrice()).isEqualByComparingTo("6700000");
        assertThat(pump.bids().get(0).total()).isEqualByComparingTo("13400000");
        assertThat(pump.winnerBin()).isEqualTo("900000000001");
        assertThat(pump.failReason()).isNull();
    }

    /**
     * Под «Определить победителем» — таблица бенефициарного владельца (живой протокол 17604410-1, планшеты, 10 шт.): его ИИН —
     * не победитель; победитель — из «заключить договор … с», второе место — из своей таблицы; цены — за единицу.
     */
    @Test
    void konkurs_beneficiaryTable_isNotWinner() throws IOException {
        LotProtocol l = ProtocolParser.parse(bytes("konkurs-beneficiary.html")).lots().get("87170063-ОК1");
        assertThat(l.bids()).extracting(ProtocolBid::bin).containsExactly("900000000001", "900000000002");
        assertThat(l.bids()).extracting(ProtocolBid::unitPrice).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("333925"), new BigDecimal("344470"));
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("3339250");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.secondBin()).isEqualTo("900000000002");
    }

    /**
     * Заявок несколько, а «Определить победителем» нет (живой протокол 17604971-1, мотовездеходы): победитель — тот, с кем
     * «заключить договор», название сверяется без учёта вида кавычек; строки цен без номера (отклонённые) — не участники.
     */
    @Test
    void konkurs_noWinnerCaption_severalBids_winnerByContractLine() {
        LotProtocol l = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "1 | 7-ОК1 | Мотовездеход | 16 | 7413793.1 | 118620689.6",
                "№ лота 7-ОК1",
                "Расчет условных цен участников конкурса:",
                "1 | ИП «Поставщик 1» | 900000000001 | 118620689.6 | 5370000 | 1302413.79 | 3 | 83342400 | 78.73 | 0 | 0 | 24.09.2026 02:38:45",
                "2 | ТОО \"Поставщик 2\" | 900000000002 | 118620689.6 | 5800000 | 872413.79 | 2.7 | 90294400 | 38.84 | 0 | 0 | 22.09.2026 14:35:47",
                " | ТОО \"Поставщик 3\" | 900000000003 | 118620689.6 | 6200000 | 0 | 3 | 96224000 | 90 | 0 | 0 | 23.09.2026 23:38:25",
                "Потенциальный поставщик, занявший второе место:",
                "1 | ТОО \"Поставщик 2\" | 900000000002",
                "1. Заказчику ГУ «Войсковая часть» в сроки, установленные Законом, заключить договор о государственных закупках с ИП \"Поставщик 1\"."))
                .lots().get("7-ОК1");
        assertThat(l.bids()).extracting(ProtocolBid::bin).containsExactly("900000000001", "900000000002");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.secondBin()).isEqualTo("900000000002");
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("85920000");
    }

    /** Таблица бенефициара и строки «заключить договор» нет — победителя нет: чужой ИИН победителем не становится. */
    @Test
    void konkurs_beneficiaryWithoutContractLine_noWinner() {
        LotProtocol l = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "1 | 8-ОК1 | Планшет | 10 | 351500 | 3515000",
                "№ лота 8-ОК1",
                "Расчет условных цен участников конкурса:",
                "1 | ТОО \"Поставщик 1\" | 900000000001 | 3515000 | 333925 | 0 | 0.5 | 3322553.75 | 27.52 | 0 | 0 | 21.09.2026 16:52:07",
                "1. Определить победителем по лоту №8-ОК1:",
                "1 | Владелец 1 | 800000000001 | 000000000 РК | 100",
                "1. Заказчику ГУ «Управление» в сроки, установленные Законом, заключить договор о государственных закупках с ТОО \"Другой\"."))
                .lots().get("8-ОК1");
        assertThat(l.winnerBin()).as("ни таблица (ИИН бенефициара), ни название из договора — не участник").isNull();
        assertThat(l.bids()).hasSize(1);
    }

    /** Файл PDF целиком (а не текст) — через PDFBox; название, перенесённое на следующую строку, склеивается. */
    @Test
    void zcp_pdfBytes_andWrappedName() throws Exception {
        byte[] pdf = ReplyFixtures.textPdf(
                "Протокол об итогах 1-ЗЦП1",
                "Лот № 5-ЗЦП1",
                "Потенциальными поставщиками представлены следующие ценовые предложения: 1",
                "1 Товарищество с ограниченной",
                "ответственностью Поставщик 900000000001 1000 0 2000 13.10.2026 17:42:30",
                "Признать государственную закупку по лоту № 5-ЗЦП1 несостоявшейся в связи с представлением одного ценового предложения");
        LotProtocol l = ProtocolParser.parse(pdf).lots().get("5-ЗЦП1");
        assertThat(l.bids().get(0).name()).isEqualTo("Товарищество с ограниченной ответственностью Поставщик");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("1000");
        assertThat(l.failReason()).isEqualTo(FailReason.ONE_BID);
    }

    /** Незнакомый вид и мусор — UNKNOWN, не исключение (Review Focus 1). */
    @Test
    void unknownFormatAndGarbage() {
        assertThat(ProtocolParser.parseLines(List.of("Протокол об итогах электронного аукциона № 1-ЭА1", "что-то")).format())
                .isEqualTo(ProtocolFormat.UNKNOWN);
        assertThat(ProtocolParser.parse("просто текст".getBytes(StandardCharsets.UTF_8)).format()).isEqualTo(ProtocolFormat.UNKNOWN);
        assertThat(ProtocolParser.parse(new byte[0]).format()).isEqualTo(ProtocolFormat.UNKNOWN);
    }

    /**
     * Живая проверка 2026-10-22 (17719756-1, обезличено): у другой версии протокола ЗЦП время подачи — «2026-10-05 17:57:53.501»,
     * название поставщика переносится на три строки, между строками участника — номер страницы.
     */
    @Test
    void zcp_isoDatesAndLongWrappedNames() throws Exception {
        ProtocolResult r = text("zcp-iso-dates.txt");
        assertThat(r.format()).isEqualTo(ProtocolFormat.ZCP);
        LotProtocol l = r.lots().get("83002509-ЗЦП1");
        assertThat(l.bids()).hasSize(4);
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        assertThat(l.bids().get(0).name()).isEqualTo("Товарищество с ограниченной ответственностью \"Поставщик 1\"");
        assertThat(l.bids().get(0).bin()).isEqualTo("900000000001");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("855666");
        assertThat(l.bids().get(0).total()).isEqualByComparingTo("8556660");
        assertThat(l.bids().get(0).submittedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 5, 17, 57, 53));
        assertThat(l.bids().get(3).name()).isEqualTo("Товарищество с ограниченной ответственностью \"\"Управляющая компания \"Поставщик 4\"\"");
        assertThat(l.bids().get(3).unitPrice()).isEqualByComparingTo("1077600");
        assertThat(l.secondBin()).isEqualTo("900000000002");
        assertThat(l.bids().get(1).name()).as("номер страницы внутри строки участника — мимо")
                .isEqualTo("Товарищество с ограниченной ответственностью \"Поставщик 2\"");
    }

    /** Строка участника без чисел (обрыв) не забирает следующего участника. */
    @Test
    void zcp_brokenRowDoesNotSwallowNext() {
        LotProtocol l = ProtocolParser.parseLines(List.of(
                "Протокол об итогах 5-ЗЦП1", "Лот № 5-ЗЦП1",
                "Потенциальными поставщиками представлены следующие ценовые предложения: 2",
                "1 Поставщик А",
                "2 Поставщик Б 900000000002 100 0 100 13.10.2026 10:00:00")).lots().get("5-ЗЦП1");
        assertThat(l.bids()).extracting(b -> b.place() + " " + b.name()).containsExactly("2 Поставщик Б");
    }
}
