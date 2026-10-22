package com.vladoose.nir.tenderresults;

import com.vladoose.nir.entity.FailReason;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import com.vladoose.nir.service.tenderresults.protocol.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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

    /** Цена конкурса — за лот: за единицу делится на количество из перечня лотов (Review Focus 4). */
    @Test
    void konkurs_unitPriceDividedByQuantity() {
        ProtocolResult r = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "1 | 5-ОК1 | Насос | 2 | 100 | 200",
                "№ лота 5-ОК1",
                "Информация о представленных заявках на участие в конкурсе (лоте): 1",
                "1 | Поставщик 1 | 900000000001 | 01.10.2026 10:00:00.000",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 180 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00",
                "1. Определить победителем по лоту №5-ОК1:",
                "1 | Поставщик 1 | 900000000001 | Владелец"));
        LotProtocol l = r.lots().get("5-ОК1");
        assertThat(l.bids().get(0).unitPrice()).isEqualByComparingTo("90.00");
        assertThat(l.winnerBin()).isEqualTo("900000000001");
        LotProtocol noQty = ProtocolParser.parseLines(List.of(
                "Протокол об итогах государственных закупок способом конкурса № 1-ОК1",
                "№ лота 6-ОК1",
                "Расчет условных цен участников конкурса:",
                "1 | Поставщик 1 | 900000000001 | 200 | 180 | 0 | 0 | 180 | 1 | 0 | 0 | 01.10.2026 10:00:00")).lots().get("6-ОК1");
        assertThat(noQty.bids().get(0).unitPrice()).as("количества нет — цены за единицу нет").isNull();
        assertThat(noQty.bids().get(0).total()).isEqualByComparingTo("180");
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
}
