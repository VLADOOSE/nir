package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.CannotReason;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Зоны честности матча на живом реестре. */
@SpringBootTest
@Transactional
class RegistryMatchConfidenceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired RegistryMatchService service;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private TenderLot lot(String name, String spec, TechSpecStatus status) {
        Tender t = new Tender();
        t.setTenderNumber("CONF-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(name);
        l.setRequiredSpec(spec);
        l.setTechSpecStatus(status);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    /**
     * Описание «вакуумный» поднимает вакуумный насос на первое место с отрывом — ради этого
     * qualifier и вводился.
     *
     * <p><b>Ожидание сменилось с CONFIDENT на «первый С ОТРЫВОМ + SHORTLIST» (Task 6,
     * 2026-08-02) — осознанно, а не ради зелёного.</b> Зона теперь смотрит не только на скор,
     * но и на число равноправных записей: слово «насос» ПОЛНОСТЬЮ покрывают 72 записи реестра,
     * и объявить одну из них ответом с процентом нельзя — это ровно тот режим отказа, ради
     * устранения которого задача и делалась (20 родовых лотов из 30 показывали процент).
     *
     * <p>Соблазн «сделать rivals умнее» — считать соперниками только тех, кто объясняет ТЗ не
     * хуже лучшего кандидата, — ПРОВЕРЕН НА РАЗМЕЧЕННОМ НАБОРЕ И ОТВЕРГНУТ: корректность зоны
     * 58/71 → 52/71, а GENERIC-лотов с ложным процентом снова 7 (было 0). Причина: у родового
     * лота и ТЗ родовое («Бинт» + «стерильный, марлевый»), оно сужает круг соперников, ничего
     * при этом не различая.
     *
     * <p>⚠️ <b>Оговорка, без которой прошлая редакция этого комментария вводила в заблуждение.</b>
     * Здесь стояло, будто размеченный набор ТРЕБУЕТ шорт-лист для «родовое имя + различающее ТЗ».
     * Это не так: ближайший аналог в наборе — «Стерилизатор» + ТЗ «плазменного стерилизатора
     * Lowtem» (топ 0.6514, rivals=103) — размечен как <b>РУ</b>, то есть набор ХОЧЕТ там
     * уверенности, а не даёт её именно {@code rivals}. Так что SHORTLIST на этом лоте — не
     * «правильный ответ по разметке», а ИЗВЕСТНОЕ ОГРАНИЧЕНИЕ {@code rivals}: гард считает
     * соперников по имени и не умеет засчитывать различающую силу ТЗ, а сделать его умнее не
     * получилось (см. цифры выше). Ограничение зафиксировано, чтобы будущая работа его снимала,
     * а не считала замыслом.
     *
     * <p>Проверяется поэтому РАНЖИРОВАНИЕ (ради него qualifier и вводился) — отрыв верного
     * кандидата от следующего, а не просто «список непустой»: прежняя редакция ассертила
     * {@code != CANNOT}, что при rivals=72 выполняется ВСЕГДА, и регрессия с 0.77 до 0.20
     * прошла бы незамеченной.
     */
    @Test
    void strongQualifierPutsMatchingCandidateFirstWithGap() {
        TenderLot l = lot("Насос", "вакуумный, производительность более 1500 л/с", null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getCandidates()).hasSizeGreaterThan(1);
        assertThat(r.getCandidates().get(0).getName().toLowerCase()).contains("вакуумн");
        assertThat(r.getCandidates().get(0).getScore() - r.getCandidates().get(1).getScore())
                .describedAs("qualifier «вакуумный» даёт ОТРЫВ, а не просто первое место "
                        + "(замер 2026-08-02: 0.7700 против 0.5200)")
                .isGreaterThan(0.2);
        assertThat(r.getConfidence())
                .describedAs("уверенность блокирует rivals=72: слово «насос» полностью покрывают "
                        + "72 записи реестра")
                .isEqualTo(MatchConfidence.SHORTLIST);
    }

    /** Генерик: кандидаты есть и они верные, но неразличимы между собой. */
    @Test
    void genericLotIsShortlistNotConfident() {
        TenderLot l = lot("Перчатки", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isNotEqualTo(MatchConfidence.CONFIDENT);
        assertThat(r.getCandidates()).isNotEmpty();
    }

    /** Ничего не нашли — честно CANNOT/NO_CANDIDATES, а не пустой список с видом уверенности.
     *  Имя без единого слова реестра: «квантовый» брать НЕЛЬЗЯ — это реальное слово реестра
     *  (df=2, «Аппарат квантовой терапии "Витязь"»), лот перестаёт быть ненайденным. */
    @Test
    void noCandidatesGivesCannot() {
        TenderLot l = lot("Криптовалютный майнер", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.NO_CANDIDATES);
    }

    /**
     * Слов лота нет в реестре → отбор идёт по ОБРЫВКУ запроса, и это отдельная причина, а не
     * «слабый матч». «Криптовалютный майнер квантовый»: «криптовалютный» и «майнер» в реестре
     * не встречаются ни разу, остаётся одно «квантовый» (df=2) — и прежняя версия уверенно
     * отвечала «Аппаратом квантовой терапии "Витязь"».
     *
     * <p>Единственное покрытие {@code QUERY_NOT_IN_REGISTRY} — пятой причины, введённой этой
     * же задачей. Отличие от {@link #noCandidatesGivesCannot()}: там выдача ПУСТА, здесь она
     * непуста, но собрана по одному уцелевшему слову.
     */
    @Test
    void queryWordsAbsentFromRegistryAreReportedAsSuch() {
        TenderLot l = lot("Криптовалютный майнер квантовый", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.QUERY_NOT_IN_REGISTRY);
    }

    /**
     * Токенный путь ОБЯЗАН уметь говорить CONFIDENT, и гард {@code rivals} обязан быть ЖИВЫМ —
     * иначе его можно затянуть до немоты, и ни один юнит-тест этого не заметит.
     *
     * <p><b>Фикстуры выбраны по измеренному {@code rivals}, а не «по смыслу» — предыдущая
     * редакция этого теста свою же заявку не выполняла.</b> Она брала «Бокс микробиологической
     * безопасности» + «II класс», у которого {@code rivals = 0}: условие {@code rivals <= MAX_RIVALS}
     * при {@code MAX_RIVALS = 0} обращается в {@code 0 <= 0} — тест оставался ЗЕЛЁНЫМ на той самой
     * мутации, ради которой писался, а его javadoc утверждал обратное. Ловится такое только
     * прогоном мутации, а не рассуждением.
     *
     * <p>Здесь фикстуры с {@code rivals} внутри рабочего диапазона (замерено запросом к реестру):
     * <ul>
     *   <li>«Система факоэмульсификационная» — {@code rivals = 3}, топ 0.5671: краснеет при
     *       {@code MAX_RIVALS} 0, 1 и 2;</li>
     *   <li>«Экскаватор» (стоматологический) — {@code rivals = 1}, топ 0.6759: краснеет при
     *       {@code MAX_RIVALS = 0}, зато с большим запасом по скору (0.126 против 0.017),
     *       поэтому переживёт дрейф реестра, если первая фикстура станет хрупкой.</li>
     * </ul>
     *
     * <p>Имя И описание берутся ДОСЛОВНО из golden-набора (обе строки размечены как РУ и
     * попадают в топ-5 верной записью). Это существенно: без описания qualifier-бонус исчезает
     * и «Система факоэмульсификационная» перестаёт быть CONFIDENT — на этом первая попытка
     * зафиксировать фикстуру и споткнулась.
     *
     * <p><b>Мутация ПРОГНАНА, а не выведена</b> (2026-08-03): при {@code MAX_RIVALS = 0} этот тест
     * краснеет; значение возвращено.
     */
    @Test
    void tokenPathStaysConfidentAndRivalsGuardIsLive() {
        LotRegistryMatchResponse phaco = service.matchForLotUi(
                lot("Система факоэмульсификационная", "для микрохирургии глаза", null).getId(), 5);
        assertThat(phaco.getConfidence())
                .describedAs("rivals=3 — краснеет, если MAX_RIVALS затянуть до 2 и ниже")
                .isEqualTo(MatchConfidence.CONFIDENT);
        assertThat(phaco.getCannotReason()).isNull();

        LotRegistryMatchResponse excavator = service.matchForLotUi(
                lot("Экскаватор", "стоматологический", null).getId(), 5);
        assertThat(excavator.getConfidence())
                .describedAs("rivals=1 — краснеет при MAX_RIVALS=0; запас по скору больше")
                .isEqualTo(MatchConfidence.CONFIDENT);
    }

    /**
     * Разобранное ТЗ превращает слабый матч в уверенный — пара к
     * {@link #weakMatchWithoutTechSpecAsksForIt()}: ТОТ ЖЕ лот без описания даёт 0.2527 и
     * честный CANNOT с подсказкой «разберите ТЗ», а с «II класс» — 0.7490 и CONFIDENT.
     * Ради этой пары фикстура и держится: она показывает, что подсказка ведёт к результату,
     * а не в никуда.
     *
     * <p>⚠️ Гардом {@code MAX_RIVALS} этот тест НЕ является, хотя прошлая редакция это
     * утверждала: у лота {@code rivals = 0}, поэтому {@code rivals <= MAX_RIVALS} выполняется
     * даже при {@code MAX_RIVALS = 0}. Живость гарда проверяет
     * {@link #tokenPathStaysConfidentAndRivalsGuardIsLive()}.
     */
    @Test
    void parsedSpecTurnsWeakBoxIntoConfident() {
        TenderLot l = lot("Бокс микробиологической безопасности", "II класс", null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CONFIDENT);
        assertThat(r.getCannotReason()).isNull();
        assertThat(r.getCandidates().get(0).getScore()).isGreaterThanOrEqualTo(0.55);
    }

    /**
     * Слабый матч + ТЗ не пытались брать → подсказка «разберите ТЗ».
     *
     * <p><b>Обоснование переписано 2026-08-02 (fix-round 1): прежнее устарело и врало.</b>
     * Там было «топ-кандидат 0.2629 — ниже SHORTLIST_MIN (0.30)». Сегодня оба числа другие
     * и, главное, тест держится на ДРУГОМ механизме. Замер на живом реестре: топ
     * («Транспортный бокс с активным охлаждением») даёт <b>0.2527</b>, а {@code SHORTLIST_MIN}
     * равен 0.30 не по совпадению, а после фикса C1.
     *
     * <p>Кейс лежит в CANNOT по ДВУМ условиям сразу, и знать надо оба:
     * <ul>
     *   <li>0.2527 < {@code SHORTLIST_MIN} (0.30) — скор слабый;</li>
     *   <li><b>rivals = 0</b> &lt; {@code SHORTLIST_IF_RIVALS} (2) — ни одна запись реестра не
     *       покрывает запрос целиком, поэтому спасение «родовой лот, покажи шорт-лист» не
     *       срабатывает. Это и есть настоящая причина: у лота нет ни хорошего ответа, ни
     *       семейства равноправных.</li>
     * </ul>
     * Верный ламинарный бокс появляется только с разобранным ТЗ — см.
     * {@link #parsedSpecTurnsWeakBoxIntoConfident()}, где тот же лот с «II класс» даёт 0.7490
     * и честный CONFIDENT. Именно поэтому подсказка «разберите ТЗ» здесь по делу.
     *
     * <p>⚠️ Тест УПАДЁТ, если опустить {@code SHORTLIST_MIN} ниже 0.2527 или разрешить
     * шорт-лист при rivals=0. Так и задумано: это единственное покрытие {@code NEED_TECH_SPEC}.
     */
    @Test
    void weakMatchWithoutTechSpecAsksForIt() {
        TenderLot l = lot("Бокс микробиологической безопасности", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getCandidates()).isNotEmpty();   // кандидаты есть — отличие от NO_CANDIDATES
        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.NEED_TECH_SPEC);
    }

    /**
     * Тот же слабый матч, но ТЗ уже пытались взять и не смогли → причина ДРУГАЯ:
     * не «разберите ТЗ» (разбирать нечего), а «ТЗ взять не удалось».
     *
     * <p>Отличие от {@link #noCandidatesGivesCannot()} — именно в кандидатах: здесь они
     * ЕСТЬ, но слабые (топ 0.2527 < SHORTLIST_MIN 0.30 И rivals=0 < SHORTLIST_IF_RIVALS),
     * поэтому {@code cannotReasonOf} доходит до чтения {@code lot.getTechSpecStatus()}.
     * На ненайденном лоте (0 кандидатов) ветка {@code candidates.isEmpty()} возвращает
     * NO_CANDIDATES раньше, статус ТЗ не читается вовсе — и тест был бы дубликатом соседнего.
     *
     * <p>Это единственное покрытие {@code TECH_SPEC_FAILED}. Значение поднимется в проде,
     * когда Task 7 начнёт писать {@code TechSpecStatus}: сейчас его в {@code src/main}
     * не пишет никто, так что ошибку в наборе статусов ловит только этот тест.
     */
    @Test
    void weakMatchAfterFailedTechSpecSaysSo() {
        TenderLot l = lot("Бокс микробиологической безопасности", null, TechSpecStatus.ERROR);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.TECH_SPEC_FAILED);
    }
}
