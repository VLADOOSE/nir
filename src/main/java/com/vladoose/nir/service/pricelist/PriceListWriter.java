package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceListRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;

/** Черновик прайса из разобранного файла — отдельный бин: разбор — вне транзакции (CLAUDE.md §6). */
@Component
public class PriceListWriter {

    private final PriceListRepository lists;
    private final PriceListLinker linker;

    public PriceListWriter(PriceListRepository lists, PriceListLinker linker) {
        this.lists = lists;
        this.linker = linker;
    }

    @Transactional
    public Long create(Distributor d, String fileName, String mime, byte[] bytes, String sha, ReplyFileKind kind,
                       ParsedPriceList parsed, Long inboundEmailId, String user) {
        PriceList p = PriceList.builder().market(d.getMarket()).distributor(d).status(PriceListStatus.DRAFT)
                .listDate(parsed.listDate()).validTo(parsed.validTo())
                .currency(parsed.currency() == null ? d.getMarket().currencyCode() : parsed.currency()).retail(parsed.retail())
                .columns(new ArrayList<>(parsed.columns())).ourColumn(parsed.ourColumn()).discountPct(BigDecimal.ZERO)
                .terms(parsed.terms()).fileName(fileName).mimeType(mime).sizeBytes((long) bytes.length).sha256(sha).data(bytes)
                .kind(kind).parseStatus(parsed.status())
                .parseNote(parsed.truncated() ? "Показаны первые 2 000 строк" : PriceTextLines.cut(parsed.note(), 300))
                .inboundEmailId(inboundEmailId).createdBy(user).build();
        PriceListLinker.Index ix = linker.index(d.getMarket());
        int n = 0;
        for (ParsedPriceRow r : parsed.rows()) {
            PriceListItem it = PriceListItem.builder().priceList(p).lineNo(++n).section(r.section()).name(r.name())
                    .article(r.article()).prices(new ArrayList<>(r.prices())).regText(r.regText())
                    .regNumber(PriceListRegs.number(r.regText())).noReg(PriceListRegs.none(r.regText())).fileRow(r.fileRow())
                    .build();
            it.setOurPrice(PriceListService.ourPrice(it.getPrices(), p.getOurColumn(), p.getDiscountPct()));
            linker.link(it, ix);
            p.getItems().add(it);
        }
        return lists.saveAndFlush(p).getId();
    }
}
