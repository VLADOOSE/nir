package com.vladoose.nir.email;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.KpEmailComposer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KpEmailComposerTest {

    @Autowired KpEmailComposer composer;

    private PriceRequest pr(Distributor d) {
        Tender t = Tender.builder().tenderNumber("KZ-TEST-1").market(Market.KZ)
                .source(Source.PUBLIC_TENDER).build();
        TenderLot lot = TenderLot.builder().tender(t).lotNumber(1).equipName("Аппарат ИВЛ").build();
        PriceRequest pr = PriceRequest.builder().id(777L).tender(t).distributor(d).market(Market.KZ)
                .items(List.of(PriceRequestItem.builder().tenderLot(lot).requestedQuantity(2).build()))
                .build();
        return pr;
    }

    @Test
    void greetsCompanyWhenNoContactPerson() {
        Distributor d = Distributor.builder().name("ТОО «MEDSYST»").market(Market.KZ).build();
        KpEmailComposer.Composed msg = composer.compose(pr(d));
        assertThat(msg.body()).startsWith("Здравствуйте!");
        assertThat(msg.body()).doesNotContain("Уважаемый(ая)  !");
        assertThat(msg.subject()).contains("[КП-777]");
        // подпись содержит инструкцию про ответ
        assertThat(msg.body()).containsIgnoringCase("ответ");
    }

    @Test
    void greetsPersonWhenContactPresent() {
        Distributor d = Distributor.builder().name("ТОО X").market(Market.KZ)
                .lastName("Алексеев").firstName("Константин").build();
        KpEmailComposer.Composed msg = composer.compose(pr(d));
        assertThat(msg.body()).startsWith("Уважаемый(ая) Алексеев Константин!");
    }

    @Test
    void previewSubjectHasNoToken() {
        Distributor d = Distributor.builder().name("ТОО X").market(Market.KZ).build();
        PriceRequest draft = pr(d);
        ReflectionTestUtils.setField(draft, "id", null);
        KpEmailComposer.Composed msg = composer.composeForPreview(draft);
        assertThat(msg.subject()).doesNotContain("[КП-");
        assertThat(msg.body()).startsWith("Здравствуйте!");
    }

    @Test
    void overrideKeepsServerToken() {
        // человеческая часть темы берётся из override, токен добавляет сервер отдельно —
        // проверяем контракт KpToken.subjectToken + произвольная человеческая часть
        String humanOverride = "СРОЧНО: КП по ИВЛ";
        String subject = com.vladoose.nir.util.KpToken.subjectToken(777L) + " " + humanOverride;
        assertThat(subject).startsWith("[КП-777]");
        assertThat(subject).endsWith(humanOverride);
        assertThat(com.vladoose.nir.util.KpToken.parse(subject)).contains(777L);
    }
}
