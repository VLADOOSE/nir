package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.PrivateRequestCreate;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.PrivateRequestUpdate;
import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Частная заявка — tender с source = PRIVATE_REQUEST (спека 2026-10-12): черновик создаётся сразу, шапка правится по
 * полю, лоты — только через коллекцию t.getLots() (orphanRemoval, CLAUDE.md §7).
 */
@Service
public class PrivateRequestService {

    private static final Pattern NUMBER_SUFFIX = Pattern.compile("^ЧЗ-\\d{4}-(\\d+)$");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final TenderRepository tenderRepository;
    private final FacilityRepository facilityRepository;
    private final RegistryMatchService registryMatchService;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final ClientOfferRepository clientOfferRepository;
    private final LeadRepository leadRepository;

    public PrivateRequestService(TenderRepository tenderRepository,
                                 FacilityRepository facilityRepository,
                                 RegistryMatchService registryMatchService,
                                 PriceRequestItemRepository priceRequestItemRepository,
                                 PriceRequestRepository priceRequestRepository,
                                 ClientOfferRepository clientOfferRepository,
                                 LeadRepository leadRepository) {
        this.tenderRepository = tenderRepository;
        this.facilityRepository = facilityRepository;
        this.registryMatchService = registryMatchService;
        this.priceRequestItemRepository = priceRequestItemRepository;
        this.priceRequestRepository = priceRequestRepository;
        this.clientOfferRepository = clientOfferRepository;
        this.leadRepository = leadRepository;
    }

    /** «+ Новая заявка»: пустой черновик — номер, «в работе», рынок (ставит листенер), дата (@PrePersist). */
    @Transactional
    public Tender createEmpty() {
        Tender t = Tender.builder()
                .tenderNumber(nextNumber())
                .status(PrivateRequestStatus.NEW.name())
                .source(Source.PRIVATE_REQUEST)
                .build();
        return tenderRepository.save(t);
    }

    /** Заявка по id — только частная и только текущего рынка: findById = em.find, фильтр рынка его не режет (§14). */
    public Tender findById(Long id) {
        Tender t = tenderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
        if (t.getSource() != Source.PRIVATE_REQUEST
                || (t.getMarket() != null && t.getMarket() != MarketContext.get())) {
            throw new NotFoundException("Заявка не найдена: id=" + id);
        }
        return t;
    }

    /** Одно поле шапки (сохранение по выходу из поля). Пустое значение очищает поле. */
    @Transactional
    public Tender updateField(Long id, PrivateRequestField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = findById(id);
        String v = blankToNull(value);
        switch (field) {
            case FACILITY -> {
                if (v == null) {
                    t.setFacility(null);
                } else {
                    t.setFacility(facility(parseId(v)));
                    t.setCustomerName(null);
                }
            }
            case CUSTOMER_NAME -> {
                t.setCustomerName(limit(v, 500, "Клиент"));
                if (v != null) t.setFacility(null);
            }
            case CONTACT_NAME -> t.setContactName(limit(v, 255, "Контактное лицо"));
            case CONTACT_PHONE -> t.setContactPhone(limit(v, 50, "Телефон"));
            case CONTACT_EMAIL -> {
                String email = limit(v, 255, "Почта");
                if (email != null && !EMAIL.matcher(email).matches()) {
                    throw new BadRequestException("Неверный адрес почты: " + email);
                }
                t.setContactEmail(email);
            }
            case DELIVERY_ADDRESS -> t.setDeliveryAddress(limit(v, 2000, "Куда поставка"));
            case NOTE -> t.setDescription(limit(v, 10000, "Примечание"));
        }
        return t;
    }

    /** «Сохранить в Учреждения»: клиент из текста становится учреждением; такое же название этого рынка — привязывается. */
    @Transactional
    public Tender saveClient(Long id) {
        Tender t = findById(id);
        String name = trunc(blankToNull(t.getCustomerName()), 255);
        if (name == null) throw new BadRequestException("Клиент не вписан текстом — сохранять нечего");
        Facility f = facilityRepository.findByName(name).orElse(null);   // HQL: учреждение чужого рынка аспект отрезает
        if (f == null) {
            if (facilityRepository.existsByNameAnyMarket(name)) {
                throw new BadRequestException("Название «" + name + "» уже занято клиентом другого рынка — уточните название");
            }
            f = facilityRepository.save(Facility.builder()
                    .name(name)
                    .phone(trunc(t.getContactPhone(), 50))
                    .email(trunc(t.getContactEmail(), 255))
                    .market(MarketContext.get())
                    .build());
        }
        t.setFacility(f);
        t.setCustomerName(null);
        return t;
    }

    @Transactional
    public Tender close(Long id, PrivateRequestStatus outcome) {
        if (outcome == null || !outcome.closed()) {
            throw new BadRequestException("Укажите исход: купили у нас, не купили или отменена");
        }
        Tender t = findById(id);
        t.setStatus(outcome.name());
        return t;
    }

    @Transactional
    public Tender reopen(Long id) {
        Tender t = findById(id);
        t.setStatus(PrivateRequestStatus.NEW.name());
        return t;
    }

    /** Только заявка без запросов цен и без КП клиенту. Обращение, из которого она создана, возвращается «В работу». */
    @Transactional
    public void delete(Long id, String author) {
        Tender t = findById(id);
        if (!priceRequestRepository.findByTenderId(id).isEmpty()) {
            throw new BadRequestException("Удалить нельзя: по заявке есть запросы цен поставщикам");
        }
        if (clientOfferRepository.existsByTenderId(id)) {
            throw new BadRequestException("Удалить нельзя: по заявке есть КП клиенту");
        }
        leadRepository.findFirstByPrivateRequestId(id).ifPresent(lead -> {
            lead.setPrivateRequest(null);
            if (lead.getStatus() == LeadStatus.CONVERTED) {
                LeadService.transition(lead, LeadStatus.IN_WORK, author, "заявка " + t.getTenderNumber() + " удалена");
            }
        });
        tenderRepository.delete(t);
    }

    /** Заявка со строками (импорт Excel, превращение обращения из старого диалога). Клиент необязателен. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Tender t = createEmpty();
        if (dto.getClientFacilityId() != null) t.setFacility(facility(dto.getClientFacilityId()));
        t.setDescription(dto.getNote());
        int lotNo = 1;
        for (PrivateRequestCreate.Line line : dto.getLines()) {
            t.getLots().add(TenderLot.builder()
                    .tender(t)
                    .lotNumber(lotNo++)
                    .equipName(line.getName())
                    .manufact(line.getManufact())
                    .quantity(line.getQuantity())
                    .build());
        }
        tenderRepository.flush();
        return t;
    }

    /** Правка строк целиком (старый PUT боковой панели) — уходит в задаче 4 вместе с панелью. */
    @Transactional
    public Tender update(Long id, PrivateRequestUpdate dto) {
        Tender t = findById(id);
        if (dto.getNote() != null) {
            t.setDescription(dto.getNote());
        }
        List<PrivateRequestUpdate.Line> incoming = dto.getLines() != null ? dto.getLines() : List.of();
        Set<Long> incomingIds = new HashSet<>();
        for (PrivateRequestUpdate.Line line : incoming) {
            if (line.getLotId() != null) incomingIds.add(line.getLotId());
        }
        t.getLots().removeIf(lot -> !incomingIds.contains(lot.getId())
                && priceRequestItemRepository.findByTenderLotId(lot.getId()).isEmpty());
        int lotNo = 1;
        for (PrivateRequestUpdate.Line line : incoming) {
            if (line.getName() == null || line.getName().isBlank()) continue;
            TenderLot lot = null;
            if (line.getLotId() != null) {
                for (TenderLot e : t.getLots()) {
                    if (e.getId() != null && e.getId().equals(line.getLotId())) { lot = e; break; }
                }
            }
            if (lot == null) {
                lot = TenderLot.builder().tender(t).build();
                t.getLots().add(lot);
            }
            lot.setLotNumber(lotNo++);
            lot.setEquipName(line.getName());
            lot.setManufact(line.getManufact());
            lot.setQuantity(line.getQuantity() != null ? line.getQuantity() : 1);
        }
        tenderRepository.save(t);
        return findById(id);
    }

    /** Список для старого GET (уходит в задаче 3). */
    public List<Tender> findAll() {
        return tenderRepository.findBySource(Source.PRIVATE_REQUEST);
    }

    /** Реестр-догадка по строкам для старого подбора поставщиков (уходит в задаче 7). */
    public List<PrivateRequestLineResponse> linesWithRegistration(Long tenderId) {
        Tender t = findById(tenderId);
        List<PrivateRequestLineResponse> result = new ArrayList<>();
        for (TenderLot lot : t.getLots()) {
            PrivateRequestLineResponse r = new PrivateRequestLineResponse();
            r.setLotId(lot.getId());
            r.setName(lot.getEquipName());
            r.setManufact(lot.getManufact());
            r.setQuantity(lot.getQuantity());
            List<RegistryCandidateResponse> cands =
                    registryMatchService.findCandidates(lot.getEquipName(), lot.getManufact(), 1);
            if (!cands.isEmpty()) {
                r.setTopCandidate(cands.get(0));
                r.setRegistrationStatus("REGISTERED");
            } else {
                r.setRegistrationStatus("NOT_FOUND");
            }
            result.add(r);
        }
        return result;
    }

    /** «ЧЗ-<год>-NNNN»: наибольший номер этого года на рынке + 1, а не количество — после удаления дубля не будет. */
    private String nextNumber() {
        int year = LocalDate.now(MarketContext.get().zone()).getYear();
        String prefix = "ЧЗ-" + year + "-";
        int max = 0;
        for (String n : tenderRepository.findPrivateNumbers(prefix + "%")) {
            Matcher m = NUMBER_SUFFIX.matcher(n);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("%s%04d", prefix, max + 1);
    }

    private Facility facility(Long facilityId) {
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + facilityId));
        if (f.getMarket() != null && f.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Клиент не найден: id=" + facilityId);
        }
        return f;
    }

    private static Long parseId(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Неверный id клиента: " + v);
        }
    }

    private static String limit(String v, int max, String label) {
        if (v != null && v.length() > max) {
            throw new BadRequestException(label + ": слишком длинно — не больше " + max + " символов");
        }
        return v;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
