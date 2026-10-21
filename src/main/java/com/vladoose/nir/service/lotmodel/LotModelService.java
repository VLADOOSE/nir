package com.vladoose.nir.service.lotmodel;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegistrationStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MedEquipmentRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.productwatch.fit.RegNumbers;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * «Модель в работе» лота (спека 2026-10-20-lot-model-in-work §5) — предложенная модель лота. Все пути, которые её ставят и
 * снимают (окно «📌 Модель…», «Взять в работу» в «Подборе» и комплектности, «Утвердить» в подборе по каталогу), идут через
 * эту службу: кто и когда, заметка, событие в «Истории» тендера.
 */
@Service
public class LotModelService {

    public static final int NOTE_MAX = 1000;
    private static final String NO_MAKER = "не указан";

    private final TenderLotRepository lots;
    private final MedEquipmentRepository equipment;
    private final MedRegistryRepository registry;
    private final SiteProductRepository siteProducts;
    private final DealEventService events;

    public LotModelService(TenderLotRepository lots, MedEquipmentRepository equipment, MedRegistryRepository registry,
                           SiteProductRepository siteProducts, DealEventService events) {
        this.lots = lots;
        this.equipment = equipment;
        this.registry = registry;
        this.siteProducts = siteProducts;
        this.events = events;
    }

    /** Лот своего рынка: `findById` = `em.find`, фильтр рынка его не режет (CLAUDE.md §14). */
    public TenderLot lotInMarket(Long lotId) {
        TenderLot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        if (lot.getTender().getMarket() != null && lot.getTender().getMarket() != MarketContext.get()) {
            throw new NotFoundException("Лот не найден: id=" + lotId);
        }
        return lot;
    }

    /** Поставить модель: та же модель — только заметка (если прислана); новая модель без заметки заметку очищает. */
    @Transactional
    public TenderLot apply(TenderLot lot, MedEquipment eq, String note) {
        return apply(lot, eq, note, false);
    }

    /** По ответу ИИ (спека 2026-10-21-tender-telegram-bot §6): пометка «закреплено ИИ», событие «… — по ответу ИИ». */
    @Transactional
    public TenderLot applyByAi(TenderLot lot, MedEquipment eq, String note) {
        return apply(lot, eq, note, true);
    }

    private TenderLot apply(TenderLot lot, MedEquipment eq, String note, boolean byAi) {
        String n = note(note);
        boolean same = lot.getProposedEquipment() != null && Objects.equals(lot.getProposedEquipment().getId(), eq.getId());
        if (same) {
            boolean confirmedByHuman = !byAi && lot.isModelByAi();   // человек подтвердил модель ИИ — теперь она ручная
            if (n == null && !confirmedByHuman) return lot;
            if (n != null) lot.setModelNote(n);
            if (confirmedByHuman) lot.setModelByAi(false);
            return lots.save(lot);
        }
        lot.setProposedEquipment(eq);
        lot.setModelNote(n);
        lot.setModelByAi(byAi);
        stamp(lot);
        TenderLot saved = lots.save(lot);
        events.stage(lot.getTender().getId(), DealEventService.MODEL,
                "Лот «" + lotName(lot) + "»: модель в работе — " + label(eq) + (byAi ? " — по ответу ИИ" : ""), lot.getId());
        return saved;
    }

    @Transactional
    public TenderLot clear(TenderLot lot) {
        if (lot.getProposedEquipment() == null) return lot;
        lot.setProposedEquipment(null);
        lot.setModelNote(null);
        lot.setModelByAi(false);
        stamp(lot);
        TenderLot saved = lots.save(lot);
        events.stage(lot.getTender().getId(), DealEventService.MODEL, "Лот «" + lotName(lot) + "»: модель снята", lot.getId());
        return saved;
    }

    @Transactional
    public TenderLot clearModel(Long lotId) {
        return clear(lotInMarket(lotId));
    }

    @Transactional
    public TenderLot setNote(Long lotId, String note) {
        TenderLot lot = lotInMarket(lotId);
        if (lot.getProposedEquipment() == null) throw new BadRequestException("У лота нет модели в работе — сначала закрепите модель");
        lot.setModelNote(note(note));
        return lots.save(lot);
    }

    @Transactional
    public TenderLot pinRegistry(Long lotId, String regNumber, String note) {
        TenderLot lot = lotInMarket(lotId);
        kzOnly();
        MedRegistry reg = registry.findByRegNumber(regNumber)
                .orElseThrow(() -> new NotFoundException("РУ не найдено в реестре: " + regNumber));
        return apply(lot, equipmentForRegistry(reg), note);
    }

    @Transactional
    public TenderLot pinSiteProduct(Long lotId, Long siteProductId, String note) {
        TenderLot lot = lotInMarket(lotId);
        kzOnly();
        SiteProduct p = siteProducts.findById(siteProductId).filter(x -> x.getMarket() == Market.KZ)
                .orElseThrow(() -> new NotFoundException("Товар сайта не найден: id=" + siteProductId));
        if (p.getRemovedAt() != null) throw new BadRequestException("Товар снят с сайта — выберите другой");
        return apply(lot, equipmentForSiteProduct(p), note);
    }

    /**
     * Позиция каталога по записи реестра: та, что заведена из этой записи (№ РУ и наименование реестра), иначе новая.
     * Только № РУ мало: с тем же № бывают позиции-комплектующие («Взять в работу» в комплектности) и наш товар сайта.
     */
    public MedEquipment equipmentForRegistry(MedRegistry reg) {
        String name = trim255(reg.getName());
        return equipment.findFirstByRegistrationRegNumberAndNameIgnoreCase(reg.getRegNumber(), name).orElseGet(() -> {
            MedEquipment e = new MedEquipment();
            e.setName(name);
            e.setManufact(reg.getProducer() != null && !reg.getProducer().isBlank() ? trim255(reg.getProducer()) : NO_MAKER);
            if (reg.getTechChars() != null && !reg.getTechChars().isBlank()) e.setSpec(reg.getTechChars());
            e.setRegistrationStatus(RegistrationStatus.REGISTERED);
            e.setRegistration(reg);
            e.setRegistrationCheckedAt(OffsetDateTime.now());
            e.setMarket(MarketContext.get());
            return equipment.save(e);
        });
    }

    /** Позиция каталога по нашему товару сайта — одна на товар; переименование на сайте подхватывается. */
    public MedEquipment equipmentForSiteProduct(SiteProduct p) {
        String name = trim255(p.getName());
        String maker = p.getBrand() != null && !p.getBrand().isBlank() ? trim255(p.getBrand()) : NO_MAKER;
        return equipment.findFirstBySiteProductId(p.getId()).map(e -> {
            e.setName(name);
            e.setManufact(maker);
            return e;
        }).orElseGet(() -> {
            MedEquipment e = new MedEquipment();
            e.setName(name);
            e.setManufact(maker);
            e.setSpec(p.getShortDescription());
            e.setSiteProduct(p);
            String n = RegNumbers.normalize(p.getRegistrationCert());
            MedRegistry reg = n == null ? null : registry.findByNormalizedRegNumber(n).orElse(null);
            e.setRegistrationStatus(reg != null ? RegistrationStatus.REGISTERED : RegistrationStatus.UNCHECKED);
            if (reg != null) {
                e.setRegistration(reg);
                e.setRegistrationCheckedAt(OffsetDateTime.now());
            }
            e.setMarket(Market.KZ);
            return equipment.save(e);
        });
    }

    private void kzOnly() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
    }

    private static void stamp(TenderLot lot) {
        lot.setModelSetBy(CurrentUser.label());
        lot.setModelSetAt(OffsetDateTime.now());
    }

    static String note(String note) {
        if (note == null || note.isBlank()) return null;
        String n = note.strip();
        if (n.length() > NOTE_MAX) throw new BadRequestException("Заметка длиннее " + NOTE_MAX + " символов");
        return n;
    }

    private static String label(MedEquipment e) {
        return e.getName() + (e.getManufact() == null || NO_MAKER.equals(e.getManufact()) ? "" : " (" + e.getManufact() + ")");
    }

    private static String lotName(TenderLot l) {
        String n = Objects.toString(l.getEquipName(), "без названия");
        return n.length() > 80 ? n.substring(0, 80) + "…" : n;
    }

    private static String trim255(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }
}
