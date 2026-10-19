package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplateDto;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import com.vladoose.nir.entity.ReplyTemplateRole;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.ReplyTemplateRepository;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Шаблоны ответов клиентам и подпись письма (спека 2026-10-19-client-mail-reply §6). Сохранение — весь список целиком;
 * сначала проверка всего ввода, потом запись. Метка «подставлять самому» у рынка — не больше одной каждого вида.
 */
@Service
public class ReplyTemplateService {

    static final int TITLE_MAX = 60;
    static final int BODY_MAX = 4000;
    static final int SIGNATURE_MAX = 1000;
    static final int MAX_TEMPLATES = 30;
    static final String EMPLOYEE = "{{сотрудник}}";

    private final ReplyTemplateRepository repository;
    private final ClientSmtpSettings smtp;

    public ReplyTemplateService(ReplyTemplateRepository repository, ClientSmtpSettings smtp) {
        this.repository = repository;
        this.smtp = smtp;
    }

    @Transactional(readOnly = true)
    public ReplyTemplatesResponse get() {
        Market market = MarketContext.get();
        List<ReplyTemplate> all = repository.findByMarketOrderByPositionAscIdAsc(market);
        String problem = smtp.problem(market);
        return new ReplyTemplatesResponse(dtos(all), signatureText(all),
                new ReplyTemplatesResponse.Sending(problem == null, smtp.username(), problem));
    }

    /** Шаблоны рынка без подписи — кнопки окна письма. */
    @Transactional(readOnly = true)
    public List<ReplyTemplateDto> templates(Market market) {
        return dtos(repository.findByMarketOrderByPositionAscIdAsc(market));
    }

    /** Подпись рынка для отправителя: {{сотрудник}} — его ФИО; ФИО нет — строка с плейсхолдером убирается целиком. */
    @Transactional(readOnly = true)
    public String signatureFor(Market market, String employee) {
        return render(signatureText(repository.findByMarketOrderByPositionAscIdAsc(market)), employee);
    }

    public static String render(String signature, String employee) {
        if (signature == null || signature.isBlank()) return "";
        String name = employee == null ? "" : employee.strip();
        return signature.lines()
                .filter(l -> !(name.isEmpty() && l.contains(EMPLOYEE)))
                .map(l -> l.replace(EMPLOYEE, name))
                .collect(Collectors.joining("\n")).strip();
    }

    @Transactional
    public ReplyTemplatesResponse save(ReplyTemplatesRequest req, String author) {
        Market market = MarketContext.get();
        List<ReplyTemplateDto> items = req == null || req.templates() == null ? List.of() : req.templates();
        String signature = req == null || req.signature() == null ? "" : req.signature().strip();
        List<ReplyTemplate> current = repository.findByMarketOrderByPositionAscIdAsc(market);
        Map<Long, ReplyTemplate> byId = new HashMap<>();
        for (ReplyTemplate t : current) if (t.getRole() != ReplyTemplateRole.SIGNATURE) byId.put(t.getId(), t);
        check(items, signature, byId);

        Set<Long> keep = items.stream().map(ReplyTemplateDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        // удалённые — первыми, у остающихся метки снимаются до записи: метка, переехавшая с шаблона на шаблон, иначе
        // упёрлась бы в уникальный индекс (market, role)
        for (ReplyTemplate t : byId.values()) {
            if (keep.contains(t.getId())) t.setRole(null);
            else repository.delete(t);
        }
        repository.flush();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < items.size(); i++) {
            ReplyTemplateDto d = items.get(i);
            ReplyTemplate t = d.id() == null ? ReplyTemplate.builder().market(market).build() : byId.get(d.id());
            t.setTitle(d.title().strip());
            t.setBody(d.body().strip());
            t.setRole(d.role());
            t.setPosition(i + 1);
            t.setUpdatedAt(now);
            t.setUpdatedBy(author);
            repository.save(t);
        }
        ReplyTemplate sig = current.stream().filter(t -> t.getRole() == ReplyTemplateRole.SIGNATURE).findFirst().orElse(null);
        if (signature.isEmpty()) {
            if (sig != null) repository.delete(sig);
        } else if (sig == null || !signature.equals(sig.getBody())) {
            if (sig == null) sig = ReplyTemplate.builder().market(market).title("Подпись").role(ReplyTemplateRole.SIGNATURE).position(0).build();
            sig.setBody(signature);
            sig.setUpdatedAt(now);
            sig.setUpdatedBy(author);
            repository.save(sig);
        }
        repository.flush();
        return get();
    }

    /** Весь ввод — до первой записи. */
    private static void check(List<ReplyTemplateDto> items, String signature, Map<Long, ReplyTemplate> byId) {
        if (items.size() > MAX_TEMPLATES) throw new BadRequestException("Шаблонов не больше " + MAX_TEMPLATES);
        if (signature.length() > SIGNATURE_MAX) throw new BadRequestException("Подпись длиннее " + SIGNATURE_MAX + " символов");
        Set<ReplyTemplateRole> roles = EnumSet.noneOf(ReplyTemplateRole.class);
        for (int i = 0; i < items.size(); i++) {
            ReplyTemplateDto d = items.get(i);
            String where = "шаблон № " + (i + 1);
            String title = d.title() == null ? "" : d.title().strip();
            String body = d.body() == null ? "" : d.body().strip();
            if (title.isEmpty()) throw new BadRequestException("Название пустое — " + where);
            if (title.length() > TITLE_MAX) throw new BadRequestException("Название длиннее " + TITLE_MAX + " символов — " + where);
            if (body.isEmpty()) throw new BadRequestException("Текст пустой — " + where + " «" + title + "»");
            if (body.length() > BODY_MAX) throw new BadRequestException("Текст длиннее " + BODY_MAX + " символов — " + where + " «" + title + "»");
            if (d.role() == ReplyTemplateRole.SIGNATURE) throw new BadRequestException("Подпись — отдельным полем");
            if (d.role() != null && !roles.add(d.role())) {
                throw new BadRequestException("«" + roleLabel(d.role()) + "» — только у одного шаблона");
            }
            if (d.id() != null && !byId.containsKey(d.id())) throw new BadRequestException("Шаблон не найден — " + where);
        }
    }

    static String roleLabel(ReplyTemplateRole r) {
        return switch (r) {
            case FIRST_REPLY -> "Подставлять в первое письмо";
            case OFFER -> "Подставлять в письмо с КП";
            case SIGNATURE -> "Подпись";
        };
    }

    private static List<ReplyTemplateDto> dtos(List<ReplyTemplate> all) {
        return all.stream().filter(t -> t.getRole() != ReplyTemplateRole.SIGNATURE)
                .map(t -> new ReplyTemplateDto(t.getId(), t.getTitle(), t.getBody(), t.getRole())).toList();
    }

    private static String signatureText(List<ReplyTemplate> all) {
        return all.stream().filter(t -> t.getRole() == ReplyTemplateRole.SIGNATURE).map(ReplyTemplate::getBody)
                .findFirst().orElse("");
    }
}
