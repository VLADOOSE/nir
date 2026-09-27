package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LeadCreateRequest;
import com.vladoose.nir.dto.request.LeadItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

import static com.vladoose.nir.util.LeadText.*;

/**
 * Работа с обращениями: список, карточка, ручной ввод, переходы статусов (спека §5), лента, позиции.
 * Мастер статусов — АИС: у источников с обратной записью (westmed.kz) переход ставит ext_status_pending,
 * а записывает его на сайт планировщик интеграции.
 */
@Service
public class LeadService {

    public static final int LIST_LIMIT = 300;

    private static final Set<LeadChannel> MANUAL_CHANNELS =
            EnumSet.of(LeadChannel.PHONE, LeadChannel.WHATSAPP, LeadChannel.OTHER);

    private final LeadRepository leadRepository;
    private final LeadIntakeService intake;

    public LeadService(LeadRepository leadRepository, LeadIntakeService intake) {
        this.leadRepository = leadRepository;
        this.intake = intake;
    }

    /** Новые сверху, не больше LIST_LIMIT; поиск — по имени, компании, email, теме, тексту, телефону, позициям. */
    @Transactional(readOnly = true)
    public List<Lead> list(Set<LeadStatus> statuses, LeadChannel channel, String q) {
        Pageable top = PageRequest.of(0, LIST_LIMIT);
        List<Lead> rows = channel == null
                ? leadRepository.findByStatusInOrderByReceivedAtDescIdDesc(statuses, top)
                : leadRepository.findByStatusInAndChannelOrderByReceivedAtDescIdDesc(statuses, channel, top);
        if (isBlank(q)) return rows;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");
        return rows.stream().filter(l -> matches(l, needle, digits)).toList();
    }

    @Transactional(readOnly = true)
    public long count(LeadStatus status) {
        return leadRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public Lead get(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Обращение не найдено: id=" + id));
        // аспект отсеивает чужой рынок; гард — defense-in-depth (как в WinnerAssignmentService)
        if (lead.getMarket() != null && lead.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Обращение не найдено: id=" + id);
        }
        return lead;
    }

    /** До 5 других обращений того же рынка с тем же телефоном — подсказка «этот номер уже обращался». */
    @Transactional(readOnly = true)
    public List<Lead> samePhone(Lead lead) {
        return lead.getPhoneNorm() == null ? List.of()
                : leadRepository.findTop5ByPhoneNormAndIdNotOrderByReceivedAtDesc(lead.getPhoneNorm(), lead.getId());
    }

    @Transactional(readOnly = true)
    public Optional<Lead> findByPrivateRequest(Long privateRequestId) {
        return leadRepository.findFirstByPrivateRequestId(privateRequestId);
    }

    /** Звонок / WhatsApp / другое, внесённое вручную. Сразу «В работе»: кто принял звонок, тот и ведёт. */
    @Transactional
    public Lead createManual(LeadCreateRequest req, String author) {
        LeadChannel channel = req.getChannel() != null ? req.getChannel() : LeadChannel.PHONE;
        if (!MANUAL_CHANNELS.contains(channel)) {
            throw new BadRequestException("Вручную вносится звонок, WhatsApp или другое обращение");
        }
        if (isBlank(req.getContactPhone()) && isBlank(req.getContactEmail())) {
            throw new BadRequestException("Укажите телефон или email клиента");
        }
        List<IncomingLead.Item> items = req.getItems() == null ? List.of() : req.getItems().stream()
                .filter(i -> !isBlank(i.getName()))
                .map(i -> new IncomingLead.Item(i.getName(), i.getBrand(),
                        i.getQuantity() != null ? i.getQuantity() : 1, null))
                .toList();
        IncomingLead in = new IncomingLead(LeadSources.MANUAL, null, channel, manualSubject(channel),
                OffsetDateTime.now(), req.getContactName(), req.getContactPhone(), req.getContactEmail(),
                req.getCompany(), req.getMessage(), items, LeadStatus.IN_WORK, null, author);
        return intake.ingest(in).orElseThrow();   // externalId = null → дублей не бывает
    }

    @Transactional
    public Lead take(Long id, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW), "взять в работу");
        transition(lead, LeadStatus.IN_WORK, author, null);
        return lead;
    }

    @Transactional
    public Lead close(Long id, LeadCloseReason reason, String comment, String author) {
        if (reason == null) throw new BadRequestException("Укажите причину закрытия");
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED), "закрыть");
        lead.setCloseReason(reason);
        transition(lead, LeadStatus.CLOSED, author,
                closeReasonLabel(reason) + (isBlank(comment) ? "" : " — " + comment.trim()));
        return lead;
    }

    @Transactional
    public Lead reopen(Long id, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.CLOSED), "вернуть в работу");
        lead.setCloseReason(null);
        transition(lead, lead.getPrivateRequest() != null ? LeadStatus.CONVERTED : LeadStatus.IN_WORK, author, null);
        return lead;
    }

    /** Заметка или звонок, внесённые человеком. Системные типы (RECEIVED/STATUS/SYNC/MESSAGE) — нельзя. */
    @Transactional
    public Lead addEvent(Long id, LeadEventType type, LeadDirection direction, String body, String author) {
        if (type != LeadEventType.NOTE && type != LeadEventType.CALL) {
            throw new BadRequestException("Вручную добавляется только заметка или звонок");
        }
        if (isBlank(body)) throw new BadRequestException("Текст пустой");
        if (type == LeadEventType.CALL && direction == null) {
            throw new BadRequestException("Укажите, входящий это звонок или исходящий");
        }
        Lead lead = get(id);
        LeadEvent e = lead.addEvent(type, author, body.trim());
        if (type == LeadEventType.CALL) {
            e.setDirection(direction);
            e.setChannel(LeadChannel.PHONE);
        }
        lead.setUpdatedAt(OffsetDateTime.now());   // новый ребёнок не пачкает родителя — время правки ставим сами
        return lead;
    }

    /** Позиции заменяются целиком (через коллекцию — orphanRemoval, §7). Только до превращения в заявку. */
    @Transactional
    public Lead updateItems(Long id, List<LeadItemDto> items, String author) {
        Lead lead = get(id);
        require(lead, EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK), "править позиции");
        lead.getItems().clear();
        for (LeadItemDto i : items == null ? List.<LeadItemDto>of() : items) {
            if (isBlank(i.getName())) continue;
            lead.addItem(trunc(i.getName().trim(), 500), trunc(blankToNull(i.getBrand()), 255),
                    i.getQuantity() != null ? i.getQuantity() : 1, trunc(blankToNull(i.getProductUrl()), 500));
        }
        lead.addEvent(LeadEventType.NOTE, author, "Позиции обновлены: " + lead.getItems().size() + " поз.");
        lead.setUpdatedAt(OffsetDateTime.now());
        return lead;
    }

    void require(Lead lead, Set<LeadStatus> from, String action) {
        if (!from.contains(lead.getStatus())) {
            throw new BadRequestException("Нельзя " + action + ": обращение в статусе «" + statusLabel(lead.getStatus()) + "»");
        }
    }

    void transition(Lead lead, LeadStatus to, String author, String note) {
        LeadStatus from = lead.getStatus();
        lead.setStatus(to);
        lead.addEvent(LeadEventType.STATUS, author,
                statusLabel(from) + " → " + statusLabel(to) + (note == null ? "" : ": " + note));
        if (LeadSources.writesBack(lead.getSource())) {
            String site = LeadSources.siteStatusFor(to);
            lead.setExtStatusPending(site.equals(lead.getExtStatus()) ? null : site);
        }
    }

    public static String statusLabel(LeadStatus s) {
        return switch (s) {
            case NEW -> "Новое";
            case IN_WORK -> "В работе";
            case CONVERTED -> "Заявка создана";
            case CLOSED -> "Закрыто";
        };
    }

    public static String closeReasonLabel(LeadCloseReason r) {
        return switch (r) {
            case ANSWERED -> "ответили клиенту без заявки";
            case SPAM -> "спам";
            case DUPLICATE -> "дубль";
            case NOT_OUR_PROFILE -> "не наш профиль";
            case CLIENT_DECLINED -> "клиент отказался";
            case OTHER -> "другое";
        };
    }

    static String manualSubject(LeadChannel channel) {
        return switch (channel) {
            case PHONE -> "Звонок";
            case WHATSAPP -> "WhatsApp";
            default -> "Обращение";
        };
    }

    private static boolean matches(Lead l, String needle, String digits) {
        if (contains(l.getContactName(), needle) || contains(l.getCompany(), needle)
                || contains(l.getContactEmail(), needle) || contains(l.getSubject(), needle)
                || contains(l.getMessage(), needle)) {
            return true;
        }
        if (digits.length() >= 4) {
            if (l.getPhoneNorm() != null && l.getPhoneNorm().contains(digits)) return true;
            if (l.getContactPhone() != null && l.getContactPhone().replaceAll("\\D", "").contains(digits)) return true;
        }
        return l.getItems().stream().anyMatch(i -> contains(i.getName(), needle) || contains(i.getBrand(), needle));
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
