package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.ImportQuantity;
import com.vladoose.nir.integration.LotMergeIndex;
import com.vladoose.nir.integration.SourceRemovedLots;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Пишет ОДИН импортный тендер в собственной транзакции (отдельный бин → Spring-прокси
 * даёт привязанную сессию для marketFilter и per-item коммит). subject/lots уже получены
 * ВНЕ транзакции и переданы сюда — транзакция короткая, без блокирующего сетевого I/O.
 */
@Component
public class GoszakupTenderWriter {

    public enum Result { CREATED, UPDATED }

    private final TenderRepository tenderRepository;
    private final RegionResolver regionResolver;
    private final SourceRemovedLots removedLots;
    private final Clock clock;

    @Autowired
    public GoszakupTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver,
                                SourceRemovedLots removedLots) {
        this(tenderRepository, regionResolver, removedLots, Clock.systemUTC());
    }

    public GoszakupTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver,
                                SourceRemovedLots removedLots, Clock clock) {
        this.tenderRepository = tenderRepository;
        this.regionResolver = regionResolver;
        this.removedLots = removedLots;
        this.clock = clock;
    }

    /**
     * Тендер уже заведён в АИС. Фильтр «медтовар» решает только, заводить ли НОВЫЙ: уже заведённый импорт обновляет
     * всегда — иначе тендер, у которого заказчик переписал лоты (живой случай 17758057-1, 2026-10-11), выпадал из
     * обновлений: ни времени срока, ни отмены на площадке.
     */
    @Transactional(readOnly = true)
    public boolean exists(String sourceExtId) {
        return tenderRepository.existsBySourceExtId(sourceExtId);
    }

    @Transactional
    public Result upsertOne(TrdBuyDto d, SubjectDto subj, List<LotDto> lots) {
        return upsertOne(d, subj, lots, null);
    }

    /** regionOverride — регион ПОСТАВКИ при регион-импорте (сервер уже отфильтровал по КАТО поставки). */
    @Transactional
    public Result upsertOne(TrdBuyDto d, SubjectDto subj, List<LotDto> lots, String regionOverride) {
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElse(null);
        boolean isNew = (t == null);
        if (isNew) { t = new Tender(); t.setSourceExtId(d.getNumberAnno()); }
        applyFields(t, d);
        applyRegion(t, subj);
        // заказчик может быть республиканским (юрадрес в другом регионе) — поставка важнее
        if (regionOverride != null && !regionOverride.isBlank()) t.setRegion(regionOverride);
        rebuildLots(t, lots);
        tenderRepository.save(t);
        return isNew ? Result.CREATED : Result.UPDATED;
    }

    private void applyFields(Tender t, TrdBuyDto d) {
        t.setTenderNumber(d.getNumberAnno());
        t.setSource(Source.PUBLIC_TENDER);
        t.setPlatform(TenderPlatform.GOSZAKUP);
        t.setMarket(Market.KZ);
        t.setCurrency("KZT");
        t.setFacility(null);
        t.setDescription(d.getNameRu());
        t.setTotalCost(d.getTotalSum());
        t.setPublishDate(GoszakupParse.localDate(d.getPublishDate()));
        t.setDeadline(GoszakupParse.localDate(d.getEndDate()));
        t.setDeadlineAt(ImportDeadline.parse(d.getEndDate()));   // «2026-10-09 10:25:00» — время площадки (B2)
        t.setCustomerBin(d.effectiveBin());
        String status = mapStatus(d.getRefBuyStatusId());
        // площадка может держать «Опубликовано…» и после срока — локально приём уже закрыт (по времени, если оно есть)
        if ("ACTIVE".equals(status)
                && ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone()))) {
            status = "COMPLETED";
        }
        t.setStatus(status);
    }

    /**
     * subject null — нет данных (сбой после повторов или неизвестный БИН): поля заказчика не трогаем, иначе
     * разовый сбой subject затёр бы у существующего тендера заказчика, КАТО и адрес. Регион нового тендера
     * при регион-импорте всё равно ставит regionOverride.
     */
    private void applyRegion(Tender t, SubjectDto subj) {
        if (subj == null) return;
        String customerName = subj.getNameRu();
        String address = subj.firstAddress();
        String kato = subj.firstKato();
        t.setCustomerName(customerName);
        t.setRegionKato(kato);
        if (address != null) t.setDeliveryAddress(address);
        t.setRegion(regionResolver.resolve(customerName, address)); // null допустим
    }

    /**
     * Слияние лотов вместо пересоздания. §7/§14: работаем ТОЛЬКО через коллекцию (orphanRemoval),
     * не через repository.delete.
     *
     * <p>Раньше здесь был {@code clear()} + создание заново — переимпорт стирал разобранное ТЗ,
     * предложенную модель, вид МИ и габариты. То есть вся работа оператора и фонового разбора
     * жила до следующего обновления тендера.
     *
     * <p><b>Ключ слияния — сырой номер лота площадки в {@code source_lot_code}, не разобранный int.</b>
     * Живые номера goszakup имеют вид «87197521-ОИ2» ({@code GoszakupDtoJsonTest}), в {@code Integer}
     * не разбираются, и слияние по {@code lotNumber} не срабатывало бы никогда. {@code lotNumber}
     * остаётся отображаемым полем — заполняется, только когда номер действительно числовой.
     */
    private void rebuildLots(Tender t, List<LotDto> lots) {
        if (lots == null || lots.isEmpty()) {
            // «ноль лотов» у тендера, где они были — это сбой получения (пустой/битый ответ /lots),
            // а не команда всё удалить. Исключение ловит importOne: +1 к ошибкам прогона, лоты целы.
            if (!t.getLots().isEmpty()) {
                throw new IllegalStateException("goszakup вернул 0 лотов для тендера " + t.getSourceExtId()
                        + ", у которого их " + t.getLots().size() + " — считаем сбоем получения, лоты не трогаем");
            }
            return;
        }

        // сперва ВСЕ по коду, затем оставшиеся — по однозначному имени (см. LotMergeIndex).
        // Имя обрезаем так же, как при записи в equipName — иначе длинное имя не совпадёт само с собой.
        // Лот «снят с площадки» возвращается только по коду: по имени его забрал бы новый лот площадки с тем же
        // названием, и запрос КП молча переехал бы на чужой лот (B5).
        List<TenderLot> matched = new LotMergeIndex(t.getLots())
                .matchAll(lots, d -> trunc(d.getLotNumber(), 50), d -> trunc(d.getNameRu(), 255),
                        existing -> existing.getSourceRemovedAt() == null);

        List<TenderLot> result = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            LotDto d = lots.get(i);
            String code = trunc(d.getLotNumber(), 50);
            TenderLot lot = matched.get(i);
            if (lot == null) {
                lot = new TenderLot();
                lot.setTender(t);
                lot.setTechSpecStatus(TechSpecStatus.PENDING);   // новый лот → в очередь на разбор ТЗ
            }
            // поля площадки обновляем всегда
            lot.setSourceLotCode(code);                          // «87197521-ОИ2» — ключ слияния
            lot.setLotNumber(GoszakupParse.intOrNull(d.getLotNumber()));
            lot.setEquipName(trunc(d.getNameRu(), 255));         // колонка VARCHAR(255): живое имя уже упирается в предел
            // B3: дробное/нулевое количество — пусто + пометка с сырым значением; сумма ≤ 0 — пусто
            // (CHECK max_cost > 0 ронял запись всего тендера)
            Integer qty = ImportQuantity.wholePositiveOrNull(d.getCount());
            lot.setQuantity(qty);
            // B1: площадка присылает сумму лота, «Макс. цена» — за единицу
            lot.setMaxCost(ImportQuantity.unitPriceOrNull(d.getAmount(), d.getCount()));
            lot.setSourceRemovedAt(null);                        // лот есть на площадке (в том числе вернулся)
            // площадка (спека 2026-10-22-tender-results-market-prices §12): ключ договора и цепочки попыток
            if (d.getId() != null) lot.setExtLotId(d.getId());
            if (d.getPointList() != null && !d.getPointList().isEmpty()) lot.setPlanPointId(d.getPointList().get(0));
            String note = d.getCount() != null && qty == null
                    ? ImportQuantity.note(d.getCount().stripTrailingZeros().toPlainString()) : null;
            // описание — только пока ТЗ не разобрано: разобранная техспека информативнее description_ru
            if (lot.getTechSpecStatus() != TechSpecStatus.OK) {
                lot.setRequiredSpec(ImportQuantity.withNote(note, d.getDescriptionRu()));
            }
            result.add(lot);
        }
        // всё, чего больше нет на площадке, уходит через orphanRemoval — кроме лотов с запросом КП или позицией
        // заявки (B5): их удаление упёрлось бы во внешний ключ и откатило бы весь тендер
        removedLots.keepReferenced(t.getLots(), result, OffsetDateTime.now(clock));
        t.getLots().clear();
        t.getLots().addAll(result);
    }

    /**
     * Тендер, найденный поиском нашей продукции по стране (спека 2026-10-19-product-watch §7.3): тот же upsert, что у
     * импорта ЗКО (регион — по заказчику), плюс признак «найден поиском» и способ закупки площадки (колонка — 50 символов).
     */
    @Transactional
    public Result upsertFound(TrdBuyDto d, SubjectDto subj, List<LotDto> lots, String tradeMethod) {
        Result r = upsertOne(d, subj, lots, null);
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElseThrow();
        t.setFoundByProductSearch(true);
        if (tradeMethod != null && !tradeMethod.isBlank()) t.setPurchaseType(trunc(tradeMethod, 50));
        return r;
    }

    /** Статус и срок заведённого поиском тендера, пропавшего из выдачи (отменён, приостановлен, закрыт): без лотов. */
    @Transactional
    public boolean updateStatus(String numberAnno, Integer refBuyStatusId, String endDate) {
        Tender t = tenderRepository.findBySourceExtId(numberAnno).orElse(null);
        if (t == null) return false;
        LocalDateTime at = ImportDeadline.parse(endDate);
        if (at != null) {
            t.setDeadline(at.toLocalDate());
            t.setDeadlineAt(at);
        }
        String status = mapStatus(refBuyStatusId);
        if ("ACTIVE".equals(status)
                && ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone()))) {
            status = "COMPLETED";
        }
        t.setStatus(status);
        return true;
    }

    /** null — тендера нет; иначе — заведён ли он поиском по стране. */
    @Transactional(readOnly = true)
    public Boolean foundBySearch(String numberAnno) {
        return tenderRepository.findBySourceExtId(numberAnno).map(Tender::isFoundByProductSearch).orElse(null);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** Словарь /v2/refs/ref_buy_status: 210–245 «Опубликовано…», 250+ рассмотрение/итоги, 410/420/430 отказ/пауза/отмена. */
    static String mapStatus(Integer refBuyStatusId) {
        if (refBuyStatusId == null) return "ACTIVE";
        if (refBuyStatusId == 410 || refBuyStatusId == 420 || refBuyStatusId == 430) return "CANCELLED";
        if (refBuyStatusId >= 250) return "COMPLETED"; // приём заявок закончен
        return "ACTIVE";
    }
}
