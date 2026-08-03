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

import com.vladoose.nir.integration.LotMergeIndex;

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

    public GoszakupTenderWriter(TenderRepository tenderRepository, RegionResolver regionResolver) {
        this.tenderRepository = tenderRepository;
        this.regionResolver = regionResolver;
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
        t.setCustomerBin(d.effectiveBin());
        String status = mapStatus(d.getRefBuyStatusId());
        // площадка может держать «Опубликовано…» и после дедлайна — локально приём уже закрыт
        if ("ACTIVE".equals(status) && t.getDeadline() != null && t.getDeadline().isBefore(java.time.LocalDate.now())) {
            status = "COMPLETED";
        }
        t.setStatus(status);
    }

    private void applyRegion(Tender t, SubjectDto subj) {
        String customerName = subj != null ? subj.getNameRu() : null;
        String address = subj != null ? subj.firstAddress() : null;
        String kato = subj != null ? subj.firstKato() : null;
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

        // сперва ВСЕ по коду, затем оставшиеся — по однозначному имени (см. LotMergeIndex)
        List<TenderLot> matched = new LotMergeIndex(t.getLots())
                .matchAll(lots, d -> trunc(d.getLotNumber(), 50), LotDto::getNameRu);

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
            lot.setEquipName(d.getNameRu());
            lot.setQuantity(d.getCount());
            lot.setMaxCost(d.getAmount());
            // описание — только пока ТЗ не разобрано: разобранная техспека информативнее description_ru
            if (lot.getTechSpecStatus() != TechSpecStatus.OK) {
                lot.setRequiredSpec(d.getDescriptionRu());
            }
            result.add(lot);
        }
        // всё, чего больше нет на площадке, уходит через orphanRemoval
        t.getLots().clear();
        t.getLots().addAll(result);
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
