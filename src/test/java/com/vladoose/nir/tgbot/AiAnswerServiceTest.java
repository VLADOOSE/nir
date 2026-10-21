package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestWriter;
import com.vladoose.nir.service.aianswer.AiAnswerResult;
import com.vladoose.nir.service.aianswer.AiAnswerResult.Outcome;
import com.vladoose.nir.service.aianswer.AiAnswerService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Применение блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §6). Бренд «ZZQXW» — ни один бренд поставщиков nirdb
 * в него не входит подстрокой (BrandMatch), поэтому в получателях только поставщики теста.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class AiAnswerServiceTest {

    static final String BRAND = "ZZQXW";

    @Autowired AiAnswerService service;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired DistributorRepository distributors;
    @Autowired LotModelService models;
    @Autowired PriceRequestWriter writer;
    @Autowired EntityManager em;
    TenderLot lot;
    SiteProduct dki, other;
    Distributor maker;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", BRAND);
        other = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-11", BRAND);
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId(), other.getId()));
        maker = TgFixtures.distributor(distributors, "ZZ Завод", "zz-maker@example.kz", true, false, BRAND);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private String go(SiteProduct p) { return "L" + lot.getId() + " | идти | P" + p.getId() + " | ТЗ совпадает"; }

    private TenderLot reload() { em.flush(); em.clear(); return lots.findById(lot.getId()).orElseThrow(); }

    @Test
    void goWithCandidate_pinsModel_marksAi_andPlansLetterToMaker() {
        AiAnswerResult r = service.apply("```\n" + go(dki) + "\n```");
        assertThat(r.lots()).singleElement().satisfies(l -> {
            assertThat(l.outcome()).isEqualTo(Outcome.PINNED);
            assertThat(l.text()).startsWith("✅ «ZZ Дефибриллятор» · ZZ Горбольница №7 → ZZ Дефибриллятор ДКИ-Н-12");
        });
        TenderLot l = reload();
        assertThat(l.getProposedEquipment().getSiteProduct().getId()).isEqualTo(dki.getId());
        assertThat(l.isModelByAi()).isTrue();
        assertThat(l.getModelNote()).isEqualTo("ИИ: ТЗ совпадает");
        assertThat(l.getAiVerdict()).isEqualTo(AiVerdict.GO);
        assertThat(l.getAiProduct().getId()).isEqualTo(dki.getId());
        assertThat(l.getAiBy()).isEqualTo("vlad");
        assertThat(r.letters()).singleElement().satisfies(p -> {
            assertThat(p.distributorId()).isEqualTo(maker.getId());
            assertThat(p.manufacturer()).isTrue();
            assertThat(p.lotIds()).containsExactly(lot.getId());
        });
    }

    @Test
    void manualModelIsKept() {
        models.apply(lot, models.equipmentForSiteProduct(other), "руками");
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.KEPT_MANUAL);
        assertThat(r.lots().get(0).text()).contains("замените в АИС");
        assertThat(reload().getProposedEquipment().getSiteProduct().getId()).isEqualTo(other.getId());
        assertThat(r.letters()).isEmpty();
    }

    @Test
    void aiModelIsReplacedByNextAnswer() {
        service.apply(go(other));
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.PINNED);
        assertThat(reload().getProposedEquipment().getSiteProduct().getId()).isEqualTo(dki.getId());
    }

    @Test
    void productNotCandidate_notPinned_verdictKept() {
        SiteProduct stranger = TgFixtures.product(products, "ZZ Чужой", BRAND);
        AiAnswerResult r = service.apply(go(stranger));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.PRODUCT_NOT_CANDIDATE);
        TenderLot l = reload();
        assertThat(l.getProposedEquipment()).isNull();
        assertThat(l.getAiVerdict()).isEqualTo(AiVerdict.GO);
        assertThat(l.getAiProduct()).isNull();
    }

    @Test
    void notOurs_recorded_noLetter() {
        AiAnswerResult r = service.apply("L" + lot.getId() + " | не наше | — | нужен передвижной");
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.NOT_OURS);
        assertThat(reload().getAiVerdict()).isEqualTo(AiVerdict.NO);
        assertThat(r.letters()).isEmpty();
    }

    /** Ревью ветки: в итоге (Telegram и окно АИС) причина короткая — полная остаётся у лота. */
    @Test
    void longReason_shortInResultLine_fullAtLot() {
        String why = "нужен передвижной на колёсах ".repeat(15).strip();
        AiAnswerResult r = service.apply("L" + lot.getId() + " | не наше | — | " + why);
        assertThat(r.lots().get(0).text()).hasSizeLessThan(260).endsWith("…");
        assertThat(reload().getAiNote()).isEqualTo(why);
    }

    /** «Аналог»: вывод — у лота, модель не закрепляется, писем нет. */
    @Test
    void analog_recorded_noPin_noLetter() {
        AiAnswerResult r = service.apply("L" + lot.getId() + " | аналог | — | CUSA Clarity, Integra, РУ РК-МТ-5№019119");
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.ANALOG);
        assertThat(r.lots().get(0).text()).contains("можно аналогом").contains("CUSA Clarity");
        assertThat(r.letters()).isEmpty();
        TenderLot l = reload();
        assertThat(l.getAiVerdict()).isEqualTo(AiVerdict.ANALOG);
        assertThat(l.getProposedEquipment()).isNull();
    }

    @Test
    void closedTender_notPinned() {
        Tender t = lot.getTender();
        t.setDeadlineAt(LocalDateTime.now(Market.KZ.zone()).minusHours(1));
        tenders.saveAndFlush(t);
        assertThat(service.apply(go(dki)).lots().get(0).outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(reload().getProposedEquipment()).isNull();
    }

    @Test
    void foreignMarketLot_notFound() {
        MarketContext.set(Market.RF);
        TenderLot rf = TgFixtures.lot(tenders, "ZZ РФ лот", "ZZ");
        rf.getTender().setMarket(Market.RF);
        tenders.saveAndFlush(rf.getTender());
        MarketContext.set(Market.KZ);
        assertThat(service.apply("L" + rf.getId() + " | идти | — | x").lots().get(0).outcome()).isEqualTo(Outcome.LOT_NOT_FOUND);
    }

    @Test
    void trustedWhenNoMakerWithMail_noteWhenNobody() {
        maker.setEmail(null);
        distributors.saveAndFlush(maker);
        Distributor trusted = TgFixtures.distributor(distributors, "ZZ Доверенный", "zz-t@example.kz", false, true, BRAND);
        assertThat(service.apply(go(dki)).letters()).singleElement()
                .satisfies(p -> assertThat(p.distributorId()).isEqualTo(trusted.getId()));
        trusted.setTrusted(false);
        distributors.saveAndFlush(trusted);
        AiAnswerResult r = service.apply("L" + lot.getId() + " | идти | P" + dki.getId() + " | снова");
        assertThat(r.letters()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("нет завода бренда «" + BRAND + "» с почтой"));
    }

    @Test
    void alreadyRequested_noteInsteadOfLetter() {
        service.apply(go(dki));
        TenderLot l = reload();
        writer.persist(l.getTender(), maker, List.of(new PriceRequestWriter.Line(l, l.getProposedEquipment(), 1)));
        AiAnswerResult r = service.apply(go(dki));
        assertThat(r.lots().get(0).outcome()).isEqualTo(Outcome.SAME_MODEL);
        assertThat(r.letters()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("уже запрошено"));
    }

    @Test
    void rfMarket_400() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.apply(go(dki))).isInstanceOf(BadRequestException.class).hasMessageContaining("Казахстан");
    }
}
