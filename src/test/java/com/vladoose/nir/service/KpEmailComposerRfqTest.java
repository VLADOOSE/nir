package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Строка о конечном заказчике и о вложении (спека 2026-10-12 §6.3) — без БД, шаблон рынка застаблен. */
class KpEmailComposerRfqTest {

    EmailTemplateRepository repo;
    KpEmailComposer composer;

    @BeforeEach
    void setUp() {
        repo = mock(EmailTemplateRepository.class);
        when(repo.findByMarket(any())).thenReturn(Optional.empty());
        composer = new KpEmailComposer(repo, new EmailTemplateRenderer());
    }

    private static PriceRequest pr(String customer, boolean disclose, boolean withDocument) {
        Tender t = new Tender();
        t.setTenderNumber("ЧЗ-2026-0099");
        t.setSource(Source.PRIVATE_REQUEST);
        t.setCustomerName(customer);
        t.setDeliveryAddress("г. Уральск,\n ул. Мира 5");
        t.setContactName("ZZ Айгуль Контакт");
        t.setContactPhone("+77010000000");
        TenderLot lot = new TenderLot();
        lot.setEquipName("Тонометр OMRON M2");
        lot.setManufact("OMRON");
        PriceRequestItem it = new PriceRequestItem();
        it.setTenderLot(lot);
        it.setRequestedQuantity(5);
        Distributor d = new Distributor();
        d.setName("ZZ Поставщик");
        PriceRequest pr = new PriceRequest();
        pr.setId(77L);
        pr.setTender(t);
        pr.setDistributor(d);
        pr.setMarket(Market.KZ);
        pr.setItems(new ArrayList<>(List.of(it)));
        pr.setDiscloseClient(disclose);
        pr.setWithDocument(withDocument);
        return pr;
    }

    @Test
    void noClientAndNoAttachmentLineByDefault() {
        String body = composer.compose(pr("ZZ Клиника «Здоровье»", false, false)).body();
        assertThat(body).doesNotContain("ZZ Клиника").doesNotContain("Конечный заказчик").doesNotContain("во вложении")
                .doesNotContain("ЧЗ-2026-0099").doesNotContain("ZZ Айгуль").doesNotContain("+77010000000");
    }

    @Test
    void clientLineWithPlaceAndCompany_attachmentLine_underPositions() {
        String body = composer.compose(pr("ZZ Клиника «Здоровье»", true, true)).body();
        assertThat(body)
                .contains("Конечный заказчик: ZZ Клиника «Здоровье», место поставки: г. Уральск, ул. Мира 5. "
                        + "Просим закрепить проект за ТОО «West-Med».")
                .contains("Запрос на бланке компании — во вложении.")
                .doesNotContain("ЧЗ-2026-0099").doesNotContain("ZZ Айгуль").doesNotContain("+77010000000");
        assertThat(body.indexOf("Конечный заказчик")).isGreaterThan(body.indexOf("Тонометр OMRON M2"));
    }

    @Test
    void contactPersonAloneIsNotAClient() {
        String body = composer.compose(pr(null, true, false)).body();
        assertThat(body).doesNotContain("Конечный заказчик").doesNotContain("ZZ Айгуль");
    }

    @Test
    void customTemplateWithoutNewPlaceholdersGetsLinesAfterPositions() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("Запрос").bodyTemplate("Здравствуйте!\n{{позиции}}\nСпасибо").build()));
        String body = composer.compose(pr("ZZ Клиника", true, true)).body();
        int pos = body.indexOf("Тонометр"), client = body.indexOf("Конечный заказчик"),
                att = body.indexOf("во вложении"), thanks = body.indexOf("Спасибо");
        assertThat(pos).isNotNegative();
        assertThat(pos).isLessThan(client);
        assertThat(client).isLessThan(att);
        assertThat(att).isLessThan(thanks);
    }

    /**
     * Шаблон в форме прежнего зашитого (сохранён оператором до этапа 2): «{{позиции}}⏎{{дедлайн}}Просим…». Вставленные
     * строки идут после перевода строки шаблона — абзацы через одну пустую строку, как в зашитом шаблоне, без двух пустых
     * подряд (найдено живой проверкой).
     */
    @Test
    void insertedLinesKeepTheTemplateSpacing() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ).subjectTemplate("З")
                .bodyTemplate("Здравствуйте!\n\n{{позиции}}\n{{дедлайн}}Просим указать: цену.").build()));
        String body = composer.compose(pr("ZZ Клиника", true, true)).body();
        assertThat(body).contains("— Тонометр OMRON M2 (OMRON) — 5 шт.\n\nКонечный заказчик: ZZ Клиника, место поставки: "
                + "г. Уральск, ул. Мира 5. Просим закрепить проект за ТОО «West-Med».\n\n"
                + "Запрос на бланке компании — во вложении.\n\nПросим указать: цену.")
                .doesNotContain("\n\n\n");
    }

    @Test
    void customTemplateUnchangedWhenNothingToAdd() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("Запрос").bodyTemplate("Здравствуйте!\n{{позиции}}\nСпасибо").build()));
        assertThat(composer.compose(pr("ZZ Клиника", false, false)).body())
                .isEqualTo("Здравствуйте!\n— Тонометр OMRON M2 (OMRON) — 5 шт.\n\nСпасибо");
    }

    @Test
    void savedClientLineTemplateIsUsed() {
        when(repo.findByMarket(any())).thenReturn(Optional.of(EmailTemplate.builder().market(Market.KZ)
                .subjectTemplate("З").bodyTemplate(KpEmailComposer.DEFAULT_BODY).rfqClientLine("Проект для {{клиент}}").build()));
        assertThat(composer.compose(pr("ZZ Клиника", true, false)).body())
                .contains("Проект для ZZ Клиника").doesNotContain("место поставки");
    }
}
