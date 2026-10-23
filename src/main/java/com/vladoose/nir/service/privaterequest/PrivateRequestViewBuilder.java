package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PrivateRequestLineView;
import com.vladoose.nir.dto.response.PrivateRequestListItemResponse;
import com.vladoose.nir.dto.response.PrivateRequestOfferRef;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.mapper.LeadResponseMapper;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.DealFileRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.execution.DealExecutionData;
import com.vladoose.nir.service.execution.DealExecutionViews;
import com.vladoose.nir.service.pricelist.PriceHints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** Ответ страницы заявки: шапка, позиции с РУ и признаком «уже запрашивали», обращение-источник. */
@Component
public class PrivateRequestViewBuilder {

    private static final Comparator<TenderLot> BY_LINE = Comparator
            .comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
            .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId());

    private final PriceRequestItemRepository itemRepository;
    private final MedRegistryRepository registryRepository;
    private final LeadRepository leadRepository;
    private final LeadResponseMapper leadMapper;
    private final PrivateRequestStageCalculator stages;
    private final ClientOfferRepository clientOffers;
    private final PrivateRequestProgressBuilder progress;
    private final DealFileRepository dealFiles;

    public PrivateRequestViewBuilder(PriceRequestItemRepository itemRepository, MedRegistryRepository registryRepository,
                                     LeadRepository leadRepository, LeadResponseMapper leadMapper,
                                     PrivateRequestStageCalculator stages, ClientOfferRepository clientOffers,
                                     PrivateRequestProgressBuilder progress, DealFileRepository dealFiles) {
        this.itemRepository = itemRepository;
        this.registryRepository = registryRepository;
        this.leadRepository = leadRepository;
        this.leadMapper = leadMapper;
        this.stages = stages;
        this.clientOffers = clientOffers;
        this.progress = progress;
        this.dealFiles = dealFiles;
    }

    public PrivateRequestViewResponse build(Tender t) {
        PrivateRequestViewResponse r = new PrivateRequestViewResponse();
        r.setId(t.getId());
        r.setNumber(t.getTenderNumber());
        r.setStatus(PrivateRequestStatus.of(t.getStatus()).name());
        PrivateRequestStageCalculator.Summary stage = stages.of(t);
        r.setStage(stage.code());
        r.setStageText(stage.text());
        r.setProgress(progress.build(t, stage));
        DealExecutionData exec = stage.exec();
        if (exec != null && exec.visible()) {
            r.setExecution(DealExecutionViews.toView(exec, dealFiles.findMetaByTenderId(t.getId())));
        }
        if (t.getFacility() != null) {
            r.setFacilityId(t.getFacility().getId());
            r.setFacilityName(t.getFacility().getName());
        }
        r.setCustomerName(t.getCustomerName());
        r.setClientLabel(clientLabel(t));
        r.setContactName(t.getContactName());
        r.setContactPhone(t.getContactPhone());
        r.setContactEmail(t.getContactEmail());
        r.setDeliveryAddress(t.getDeliveryAddress());
        r.setNote(t.getDescription());
        r.setCreatedAt(t.getCreatedAt());
        leadRepository.findFirstByPrivateRequestId(t.getId()).map(leadMapper::toRef).ifPresent(r::setLead);
        r.setOffers(clientOffers.findByTenderIdOrderByIdDesc(t.getId()).stream()
                .map(o -> new PrivateRequestOfferRef(o.getId(), o.getNumber(), o.getOfferDate(), o.getStatus().name(),
                        o.getItemCount(), o.getTotalAmount(), o.getMarket().currencyCode(), o.getOrigin().name()))
                .toList());

        Set<Long> requested = new HashSet<>();
        for (PriceRequestItem i : itemRepository.findByTenderId(t.getId())) {
            requested.add(i.getTenderLot().getId());
        }
        List<TenderLot> lots = t.getLots().stream().sorted(BY_LINE).toList();
        Map<String, MedRegistry> records = records(lots);
        Map<Long, PriceHint> prices = hints(t.getMarket(), lots);
        r.setLines(lots.stream()
                .map(l -> line(l, requested.contains(l.getId()), records.get(l.getRegNumber()), prices.get(l.getId())))
                .toList());
        return r;
    }

    private PriceHints priceHints;

    /**
     * Подсказки «📒 по прайсу» (спека 2026-10-23 §6.2 п. 2) — сеттером, не конструктором: подсказки — необязательная часть
     * вида, а сервис подсказок тянет поиск моделей; без бина (тесты, собранные руками) — подсказок нет.
     */
    @Autowired(required = false)
    public void setPriceHints(PriceHints priceHints) {
        this.priceHints = priceHints;
    }

    private Map<Long, PriceHint> hints(Market m, List<TenderLot> lots) {
        Map<Long, PriceHint> out = new HashMap<>();
        if (priceHints == null || lots.isEmpty() || m == null) return out;
        List<PriceHint> found = priceHints.hints(m, lots.stream()
                .map(l -> PriceHints.Target.text(String.join(" ", Objects.toString(l.getEquipName(), ""), Objects.toString(l.getManufact(), ""))))
                .toList());
        for (int i = 0; i < lots.size(); i++) out.put(lots.get(i).getId(), found.get(i));
        return out;
    }

    /** Карточки списка: этап и число позиций — одним проходом калькулятора, обращения — одним запросом. */
    public List<PrivateRequestListItemResponse> list(List<Tender> requests) {
        Map<Long, PrivateRequestStageCalculator.Summary> summaries = stages.of(requests);
        Map<Long, Long> leadIds = new HashMap<>();
        if (!requests.isEmpty()) {
            for (Lead l : leadRepository.findByPrivateRequestIdIn(requests.stream().map(Tender::getId).toList())) {
                leadIds.putIfAbsent(l.getPrivateRequest().getId(), l.getId());
            }
        }
        return requests.stream().map(t -> {
            PrivateRequestStageCalculator.Summary s = summaries.get(t.getId());
            PrivateRequestListItemResponse r = new PrivateRequestListItemResponse();
            r.setId(t.getId());
            r.setNumber(t.getTenderNumber());
            r.setStatus(PrivateRequestStatus.of(t.getStatus()).name());
            r.setStage(s.code());
            r.setStageText(s.text());
            r.setClientLabel(clientLabel(t));
            r.setLineCount(s.lineCount());
            r.setCreatedAt(t.getCreatedAt());
            r.setLeadId(leadIds.get(t.getId()));
            return r;
        }).toList();
    }

    /** Одна позиция — ответ на правку строки и на действие с РУ. */
    public PrivateRequestLineView line(TenderLot l) {
        MedRegistry rec = l.getRegNumber() == null ? null : registryRepository.findByRegNumber(l.getRegNumber()).orElse(null);
        return line(l, l.getId() != null && itemRepository.existsByTenderLotId(l.getId()), rec,
                hints(l.getTender() == null ? null : l.getTender().getMarket(), List.of(l)).get(l.getId()));
    }

    private PrivateRequestLineView line(TenderLot l, boolean requested, MedRegistry rec, PriceHint hint) {
        PrivateRequestLineView v = new PrivateRequestLineView();
        v.setLotId(l.getId());
        v.setLineNo(l.getLotNumber());
        v.setName(l.getEquipName());
        v.setBrand(l.getManufact());
        v.setQuantity(l.getQuantity());
        v.setUnit(l.getUnit());
        v.setSpec(l.getRequiredSpec());
        v.setProductUrl(l.getProductUrl());
        v.setRegStatus(l.getRegStatus() == null ? null : l.getRegStatus().name());
        v.setRegistry(rec == null ? null : candidate(rec));
        v.setRequested(requested);
        v.setPriceList(hint);
        return v;
    }

    private Map<String, MedRegistry> records(List<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegNumber() != null) numbers.add(l.getRegNumber());
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (numbers.isEmpty()) return out;
        for (MedRegistry m : registryRepository.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    public static RegistryCandidateResponse candidate(MedRegistry m) {
        RegistryCandidateResponse c = new RegistryCandidateResponse();
        c.setRegNumber(m.getRegNumber());
        c.setName(m.getName());
        c.setProducer(m.getProducer());
        c.setCountry(m.getCountry());
        c.setRegDate(m.getRegDate());
        c.setExpirationDate(m.getExpirationDate());
        c.setUnlimited(m.getUnlimited());
        return c;
    }

    /** Как клиент показывается в списке, шапке и КП (спека §5.1). */
    public static String clientLabel(Tender t) {
        if (t.getFacility() != null && notBlank(t.getFacility().getName())) return t.getFacility().getName();
        if (notBlank(t.getCustomerName())) return t.getCustomerName().trim();
        if (notBlank(t.getContactName())) return t.getContactName().trim();
        if (notBlank(t.getContactPhone())) return t.getContactPhone().trim();
        return "Клиент не указан";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
