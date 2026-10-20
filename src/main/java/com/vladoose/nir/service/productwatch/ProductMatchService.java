package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.productwatch.fit.ForeignBrand;
import com.vladoose.nir.service.productwatch.fit.LotFit;
import com.vladoose.nir.service.productwatch.fit.SpecFit;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import com.vladoose.nir.util.LotDescriptiveText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Отметки «наш товар» одного тендера (спека 2026-10-19-product-watch §4.3, §5.6): правила по тому, что лежит в базе
 * (название объявления, лоты; разобранное ТЗ — у лотов со статусом OK), результат — заменой набора отметок тендера.
 * Источник строки — первой отметки; ТЗ сверено — когда отметка посчитана по ТЗ.
 */
@Service
public class ProductMatchService {

    public record Result(long tenderId, ProductMatchLevel level, int lots, boolean hadMarks) {}

    private final TenderRepository tenders;
    private final TenderLotProductMatchRepository matches;
    private final CatalogSource catalog;

    public ProductMatchService(TenderRepository tenders, TenderLotProductMatchRepository matches, CatalogSource catalog) {
        this.tenders = tenders;
        this.matches = matches;
        this.catalog = catalog;
    }

    @Transactional
    public Result evaluate(long tenderId, ProductMatchSource source) {
        Tender t = tenders.findById(tenderId).orElse(null);
        if (t == null) return new Result(tenderId, null, 0, false);
        List<LotInput> inputs = new ArrayList<>();
        for (TenderLot l : t.getLots()) {
            if (l.getSourceRemovedAt() != null || l.getId() == null) continue;
            boolean spec = l.getTechSpecStatus() == TechSpecStatus.OK;
            // разобранное ТЗ заменило описание площадки — берём его из шапки ТЗ (исключения групп смотрят на текст лота)
            String description = spec ? LotDescriptiveText.lotDescription(l.getRequiredSpec()) : l.getRequiredSpec();
            inputs.add(new LotInput(l.getId(), l.getEquipName(), description, spec ? l.getRequiredSpec() : null));
        }
        CatalogSnapshot snap = catalog.get();
        List<LotMatch> result = ProductMatcher.match(snap, t.getDescription(), inputs);
        Map<Long, LotInput> inputById = new HashMap<>();
        inputs.forEach(i -> inputById.put(i.lotId(), i));
        Map<Long, String> textByLot = new HashMap<>();
        List<TenderLotProductMatch> existing = matches.findByTenderId(tenderId);
        boolean hadMarks = !existing.isEmpty();
        Map<String, TenderLotProductMatch> byKey = new HashMap<>();
        for (TenderLotProductMatch m : existing) byKey.put(m.getLotId() + "/" + m.getGroupId(), m);
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> keep = new HashSet<>();
        Set<Long> lots = new HashSet<>();
        ProductMatchLevel max = null;
        for (LotMatch m : result) {
            String key = m.lotId() + "/" + m.groupId();
            keep.add(key);
            lots.add(m.lotId());
            TenderLotProductMatch row = byKey.get(key);
            if (row == null) {
                row = TenderLotProductMatch.builder().tenderId(tenderId).lotId(m.lotId()).groupId(m.groupId())
                        .source(source).matchedAt(now).build();
            }
            row.setWord(trunc(m.word(), 100));
            row.setEvidence(trunc(m.evidence(), 1000));
            row.setProductId(m.productId());
            row.setSimilar(new ArrayList<>(m.similar()));
            if (m.specUsed() && row.getSpecCheckedAt() == null) row.setSpecCheckedAt(now);
            row.setUpdatedAt(now);
            ProductMatchLevel level = m.level();
            LotInput in = inputById.get(m.lotId());
            String text = textByLot.computeIfAbsent(m.lotId(), id -> fitText(in.spec(), in.description()));
            LotFit fit = fitFor(snap, m.groupId(), text);
            applyFit(row, fit, now);
            if (fit != null && level == ProductMatchLevel.TYPE && fit.verdict() == FitVerdict.FITS) level = ProductMatchLevel.FITS;
            row.setLevel(level);
            matches.save(row);
            max = ProductMatchLevel.max(max, level);
        }
        for (TenderLotProductMatch m : existing) {
            if (!keep.contains(m.getLotId() + "/" + m.getGroupId())) matches.delete(m);
        }
        return new Result(tenderId, max, lots.size(), hadMarks);
    }

    /**
     * Текст для сверки (спека 2026-10-20-product-spec-fit §5.1): разобранное ТЗ — раздел характеристик; нет ТЗ — описание
     * лота с площадки, строки склеены. `spec` — `required_spec` при `tech_spec_status = OK`, иначе null. Общий для отметки,
     * «Требований ТЗ», «Для ИИ» и гейта: качество проверяется тем же кодом, что работает (CLAUDE.md §14).
     */
    public static String fitText(String spec, String description) {
        return spec != null && !spec.isBlank() ? SpecRequirements.body(spec) : SpecRequirements.reflowText(Objects.toString(description, ""));
    }

    /** Сверка лота с товарами группы вида (спека §6); группа «Наши бренды и модели» или неизвестная — null. */
    public static LotFit fitFor(CatalogSnapshot snap, long groupId, String text) {
        GroupRule rule = snap.group(groupId);
        if (rule == null || rule.brandGroup()) return null;
        return SpecFit.evaluate(SpecRequirements.extract(text), snap.groupCards(groupId),
                ForeignBrand.detect(text, snap.ours(), snap.knownBrands()));
    }

    private static void applyFit(TenderLotProductMatch row, LotFit fit, OffsetDateTime now) {
        FitCandidate best = fit == null ? null : fit.best();
        row.setFit(fit == null ? null : fit.verdict());
        row.setFitProductId(best == null ? null : best.productId());
        row.setFitMet(best == null ? null : best.met());
        row.setFitTotal(best == null ? null : best.total());
        row.setFitReason(best == null ? null : trunc(best.reason(), 300));
        row.setForeignBrand(fit == null ? null : trunc(fit.foreignBrand(), 100));
        row.setFitCandidates(fit == null ? new ArrayList<>() : new ArrayList<>(fit.candidates()));
        row.setFitCheckedAt(fit == null ? null : now);
    }

    /** ТЗ лота недоступно насовсем (нет файла, не читается, 3 неудачи) — сверка закончена, отметка остаётся по названию. */
    @Transactional
    public void markSpecChecked(long lotId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (TenderLotProductMatch m : matches.findByLotId(lotId)) {
            if (m.getSpecCheckedAt() == null) {
                m.setSpecCheckedAt(now);
                matches.save(m);
            }
        }
    }

    /** Сообщено в Telegram (или пересчёт молча, спека §8) — уровень не понижается. */
    @Transactional
    public void markNotified(long tenderId, ProductMatchLevel level) {
        tenders.findById(tenderId).ifPresent(t -> {
            t.setProductNotifiedLevel(ProductMatchLevel.max(t.getProductNotifiedLevel(), level));
            t.setProductNotifiedAt(OffsetDateTime.now());
        });
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
