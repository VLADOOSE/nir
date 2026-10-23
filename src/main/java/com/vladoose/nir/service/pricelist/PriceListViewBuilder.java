package com.vladoose.nir.service.pricelist;

import com.vladoose.nir.dto.response.PriceListListItem;
import com.vladoose.nir.dto.response.PriceListView;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PriceListViewBuilder {

    private final PriceListService service;
    private final PriceListRepository lists;
    private final PriceListItemRepository items;
    private final SiteProductRepository products;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final CurrencyRates rates;
    private final String siteUrl;

    public PriceListViewBuilder(PriceListService service, PriceListRepository lists, PriceListItemRepository items,
                                SiteProductRepository products, MedEquipmentRepository equipment, MedRegistryRepository registry,
                                CurrencyRates rates, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.service = service;
        this.lists = lists;
        this.items = items;
        this.products = products;
        this.equipment = equipment;
        this.registry = registry;
        this.rates = rates;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public PriceListView view(Long id) {
        PriceList p = service.guard(id);
        Market m = p.getMarket();
        LocalDate today = LocalDate.now(m.zone());
        String cur = p.getCurrency();
        boolean foreign = cur != null && !cur.equals(m.currencyCode()) && !"MIXED".equals(cur);
        BigDecimal our = foreign ? rates.our(m, cur).orElse(null) : null;
        CurrencyRates.Today td = foreign ? rates.today(m, cur).orElse(null) : null;
        Map<String, PriceListItem> previous = previous(p);
        Map<Long, SiteProduct> sp = new HashMap<>();
        products.findAllById(p.getItems().stream().map(PriceListItem::getSiteProductId).filter(Objects::nonNull).toList())
                .forEach(s -> sp.put(s.getId(), s));
        Map<Long, MedEquipment> eq = new HashMap<>();
        equipment.findAllById(p.getItems().stream().map(PriceListItem::getMedEquipmentId).filter(Objects::nonNull).toList())
                .forEach(e -> eq.put(e.getId(), e));
        // РУ строк — одним запросом: вид пересобирается после каждой правки поля, а строк бывает 2 000
        Map<String, MedRegistry> regs = new HashMap<>();
        Set<String> numbers = new HashSet<>();
        for (PriceListItem r : p.getItems()) if (r.getRegNumber() != null) numbers.add(RegNumbers.normalize(r.getRegNumber()));
        if (!numbers.isEmpty()) {
            for (MedRegistry rec : registry.findByNormalizedRegNumberIn(numbers)) regs.putIfAbsent(RegNumbers.normalize(rec.getRegNumber()), rec);
        }
        List<PriceListView.Row> rows = new ArrayList<>();
        for (PriceListItem r : p.getItems()) {
            MedRegistry rec = r.getRegNumber() == null ? null : regs.get(RegNumbers.normalize(r.getRegNumber()));
            String regStatus = PriceListRegs.status(r.isNoReg(), r.getRegNumber(), rec, today);
            PriceListItem prev = PriceListKeys.of(r).stream().map(previous::get).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal prevPrice = prev == null ? null : prev.getOurPrice();
            BigDecimal pct = prevPrice == null || prevPrice.signum() == 0 || r.getOurPrice() == null ? null
                    : r.getOurPrice().subtract(prevPrice).multiply(BigDecimal.valueOf(100)).divide(prevPrice, 1, RoundingMode.HALF_UP);
            SiteProduct s = r.getSiteProductId() == null ? null : sp.get(r.getSiteProductId());
            MedEquipment e = r.getMedEquipmentId() == null ? null : eq.get(r.getMedEquipmentId());
            rows.add(new PriceListView.Row(r.getId(), r.getLineNo(), r.getSection(), r.getName(), r.getModel(), r.getArticle(),
                    r.getPrices(), r.getOurPrice(), foreign ? RateMath.convert(r.getOurPrice(), our) : null,
                    td == null ? null : RateMath.convert(r.getOurPrice(), td.rate()), r.getRegText(), r.getRegNumber(), regStatus,
                    rec == null ? null : rec.getExpirationDate(), r.isNoReg(), r.getSiteProductId(), s == null ? null : s.getName(),
                    s == null || s.getSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, s.getSlug()), r.getMedEquipmentId(),
                    e == null ? null : e.getName(), prevPrice, prev == null ? null : PriceListOrder.date(prev.getPriceList()), pct, r.getFileRow()));
        }
        return new PriceListView(p.getId(), p.getStatus().name(), p.getDistributor().getId(), p.getDistributor().getName(),
                p.getFileName(), p.getKind().name(), p.getParseStatus().name(), p.getParseNote(), p.getListDate(), p.getValidTo(),
                p.getValidTo() != null && p.getValidTo().isBefore(today), cur, p.isRetail(), p.getColumns(), p.getOurColumn(),
                p.getDiscountPct(), p.getTerms(), m.currencyCode(), our, td == null ? null : td.rate(), td == null ? null : td.date(),
                p.getCreatedBy(), p.getCreatedAt(), p.getActivatedBy(), p.getActivatedAt(), rows);
    }

    /** «Было …»: по каждому ключу — строка самого нового из более старых действующих прайсов того же поставщика. */
    private Map<String, PriceListItem> previous(PriceList p) {
        Map<String, PriceListItem> out = new HashMap<>();
        List<PriceListItem> others = new ArrayList<>(items.findOtherActiveOfDistributor(p.getDistributor().getId(), p.getId()));
        others.sort(Comparator.comparing(PriceListItem::getPriceList, PriceListOrder.NEWER));
        for (PriceListItem o : others) {
            if (o.getOurPrice() == null || PriceListOrder.NEWER.compare(o.getPriceList(), p) < 0) continue;   // новее — не «было»
            for (String k : PriceListKeys.of(o)) out.putIfAbsent(k, o);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<PriceListListItem> list(Long supplier) {
        LocalDate today = LocalDate.now(com.vladoose.nir.context.MarketContext.get().zone());
        List<PriceListListItem> out = new ArrayList<>();
        for (Object[] r : lists.findRows()) {
            Long distributorId = (Long) r[1];
            if (supplier != null && !supplier.equals(distributorId)) continue;
            LocalDate validTo = (LocalDate) r[5];
            out.add(new PriceListListItem((Long) r[0], distributorId, (String) r[2], ((PriceListStatus) r[3]).name(), (LocalDate) r[4],
                    validTo, validTo != null && validTo.isBefore(today), (String) r[6], (String) r[7], ((ReplyFileKind) r[8]).name(),
                    ((ReplyParseStatus) r[9]).name(), (String) r[10], (OffsetDateTime) r[11], ((Number) r[12]).longValue()));
        }
        return out;
    }
}
