package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ReplyTemplateController;
import com.vladoose.nir.dto.request.ReplyTemplatesRequest;
import com.vladoose.nir.dto.response.ReplyTemplateDto;
import com.vladoose.nir.dto.response.ReplyTemplatesResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ReplyTemplate;
import com.vladoose.nir.entity.ReplyTemplateRole;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.ReplyTemplateRepository;
import com.vladoose.nir.service.ReplyTemplateService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Шаблоны ответов и подпись (спека 2026-10-19-client-mail-reply §6). */
@SpringBootTest
@Transactional
class ReplyTemplateServiceTest {

    @Autowired ReplyTemplateService service;
    @Autowired ReplyTemplateRepository repository;
    @Autowired ReplyTemplateController controller;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Своя картина в откатываемой транзакции — тест не зависит от шаблонов, которые правит оператор (CLAUDE.md §14). */
    private void clean() {
        repository.deleteAllInBatch();
        em.flush();
        em.clear();
    }

    private static ReplyTemplateDto t(Long id, String title, ReplyTemplateRole role) {
        return new ReplyTemplateDto(id, title, "Текст «" + title + "»", role);
    }

    private static ReplyTemplatesRequest req(String signature, ReplyTemplateDto... items) {
        return new ReplyTemplatesRequest(Arrays.asList(items), signature);
    }

    /** Сид V35 — пока шаблоны KZ не правили: тексты утверждены оператором 2026-10-19. Правили — только инварианты. */
    @Test
    void seed_kzTemplatesAndSignature() {
        List<ReplyTemplate> kz = repository.findByMarketOrderByPositionAscIdAsc(Market.KZ);
        Set<ReplyTemplateRole> seen = EnumSet.noneOf(ReplyTemplateRole.class);
        assertThat(kz).allSatisfy(x -> assertThat(x.getRole() == null || seen.add(x.getRole())).isTrue());
        if (kz.stream().anyMatch(x -> x.getUpdatedAt() != null)) return;
        assertThat(kz).extracting(ReplyTemplate::getTitle, ReplyTemplate::getRole).containsExactly(
                tuple("Подпись", ReplyTemplateRole.SIGNATURE),
                tuple("Первое письмо", ReplyTemplateRole.FIRST_REPLY),
                tuple("Запрос принят", null),
                tuple("КП во вложении", ReplyTemplateRole.OFFER),
                tuple("Напомнить о КП", null));
        assertThat(kz.get(0).getBody()).isEqualTo("С уважением,\n{{сотрудник}}\nWest-Med\n+7 777 075 27 70 · westmed.kz");
        assertThat(kz.get(1).getBody()).isEqualTo("Здравствуйте!\n\nСпасибо за обращение в West-Med. Подскажите, пожалуйста, "
                + "кто конечный заказчик: название учреждения и город?");
        assertThat(kz).allSatisfy(x -> assertThat(x.getBody()).doesNotContain("—"));
    }

    @Test
    void save_createsUpdatesDeletesInOrder_signatureSeparately() {
        clean();
        ReplyTemplatesResponse r = service.save(req("С уважением,\n{{сотрудник}}",
                t(null, "Первое письмо", ReplyTemplateRole.FIRST_REPLY), t(null, "Запрос принят", null)), "zz");
        assertThat(r.templates()).extracting(ReplyTemplateDto::title).containsExactly("Первое письмо", "Запрос принят");
        assertThat(r.signature()).isEqualTo("С уважением,\n{{сотрудник}}");
        Long first = r.templates().get(0).id();
        Long second = r.templates().get(1).id();

        r = service.save(req("", t(second, "Запрос принят ✓", null), t(null, "КП во вложении", ReplyTemplateRole.OFFER)), "zz");

        assertThat(r.templates()).extracting(ReplyTemplateDto::id, ReplyTemplateDto::title)
                .containsExactly(tuple(second, "Запрос принят ✓"), tuple(r.templates().get(1).id(), "КП во вложении"));
        assertThat(repository.findById(first)).isEmpty();
        assertThat(r.signature()).isEmpty();
    }

    /**
     * Метка переезжает со второго шаблона на первый: Hibernate пишет обновления в порядке строк, и первый получил бы метку,
     * пока второй её ещё держит, — уникальный индекс (market, role). Обратный переезд прошёл бы и без снятия меток.
     */
    @Test
    void save_roleMovesToEarlierTemplateInOneSave() {
        clean();
        ReplyTemplatesResponse r = service.save(req("", t(null, "А", null), t(null, "Б", ReplyTemplateRole.FIRST_REPLY)), "zz");
        Long a = r.templates().get(0).id();
        Long b = r.templates().get(1).id();
        r = service.save(req("", t(a, "А", ReplyTemplateRole.FIRST_REPLY), t(b, "Б", null)), "zz");
        assertThat(r.templates()).extracting(ReplyTemplateDto::role).containsExactly(ReplyTemplateRole.FIRST_REPLY, null);
    }

    @Test
    void save_invalidInput_400_nothingWritten() {
        clean();
        assertBad(req("", t(null, "А", ReplyTemplateRole.OFFER), t(null, "Б", ReplyTemplateRole.OFFER)),
                "«Подставлять в письмо с КП» — только у одного шаблона");
        assertBad(req("", new ReplyTemplateDto(null, " ", "Текст", null)), "Название пустое — шаблон № 1");
        assertBad(req("", new ReplyTemplateDto(null, "А", " ", null)), "Текст пустой — шаблон № 1 «А»");
        assertBad(req("", new ReplyTemplateDto(null, "x".repeat(61), "Текст", null)), "Название длиннее 60 символов — шаблон № 1");
        assertBad(req("", new ReplyTemplateDto(null, "А", "x".repeat(4001), null)), "Текст длиннее 4000 символов — шаблон № 1 «А»");
        assertBad(req("", t(null, "А", ReplyTemplateRole.SIGNATURE)), "Подпись — отдельным полем");
        assertBad(req("x".repeat(1001)), "Подпись длиннее 1000 символов");
        assertThat(repository.findByMarketOrderByPositionAscIdAsc(Market.KZ)).isEmpty();
    }

    @Test
    void save_idOfOtherMarket_400() {
        clean();
        ReplyTemplate rf = repository.save(ReplyTemplate.builder().market(Market.RF).title("РФ").body("Текст").position(1).build());
        assertBad(req("", t(rf.getId(), "РФ", null)), "Шаблон не найден — шаблон № 1");
    }

    @Test
    void render_nameReplaced_blankNameDropsLine() {
        assertThat(ReplyTemplateService.render("С уважением,\n{{сотрудник}}\nWest-Med", "Иван Петров"))
                .isEqualTo("С уважением,\nИван Петров\nWest-Med");
        assertThat(ReplyTemplateService.render("С уважением,\n{{сотрудник}}\nWest-Med", "  ")).isEqualTo("С уважением,\nWest-Med");
        assertThat(ReplyTemplateService.render("", "Иван")).isEmpty();
        assertThat(ReplyTemplateService.render(null, "Иван")).isEmpty();
    }

    @Test
    void templates_withoutSignature_signatureForMarket_rfEmpty() {
        clean();
        service.save(req("С уважением,\n{{сотрудник}}", t(null, "А", null)), "zz");
        assertThat(service.templates(Market.KZ)).extracting(ReplyTemplateDto::title).containsExactly("А");
        assertThat(service.signatureFor(Market.KZ, "Иван Петров")).isEqualTo("С уважением,\nИван Петров");
        assertThat(service.templates(Market.RF)).isEmpty();
        assertThat(service.signatureFor(Market.RF, "Иван Петров")).isEmpty();
    }

    @Test
    void sending_notConfiguredInDefaultContext() {
        ReplyTemplatesResponse.Sending s = service.get().sending();
        assertThat(s.enabled()).isFalse();
        assertThat(s.reason()).isEqualTo("Отправка писем клиентам не настроена");
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_cannotReadOrSave() {
        assertThatThrownBy(() -> controller.get()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.save(req(""))).isInstanceOf(AccessDeniedException.class);
    }

    private void assertBad(ReplyTemplatesRequest r, String message) {
        assertThatThrownBy(() -> service.save(r, "zz")).isInstanceOf(BadRequestException.class).hasMessage(message);
    }
}
