# Реестр-ориентированный импорт goszakup по orgBin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Импорт KZ-тендеров goszakup тянет объявления по реестру больниц (фильтр `orgBin`), а «медицинскость» решает по лотам, а не по имени объявления — вместо региональной KATO-ленты с keyword-предфильтром, который душил 97% реальных медтендеров.

**Architecture:** Реестр больниц = записи `facility` (рынок KZ, флаг `monitor_tenders`, БИН в поле `inn`). `GoszakupImportService.fillImport(region)` перебирает мониторимые учреждения региона, по каждому дёргает v3 GraphQL `TrdBuy(filter:{orgBin})`, фетчит лоты, прогоняет `MedicalRelevanceFilter` по лотам и апсертит тендер с регионом из реестра. Прогресс — по больницам. Старый KATO-класс остаётся в коде, но из импорта выключен.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA/Hibernate 6, MapStruct 1.5.5, Lombok, Flyway (PostgreSQL 17), Angular 21 (standalone-компоненты, инлайн-шаблоны).

## Global Constraints

- Схему менять ТОЛЬКО новыми миграциями Flyway; не править V1–V12. Следующая версия — **V13**.
- `@FilterDef` объявлен ТОЛЬКО на `Tender` — новые сущности не заводим; `Facility` уже `MarketScoped`, ничего в фильтре не трогаем.
- JPA-лоты — только через коллекцию `t.getLots()` (orphanRemoval), не `repository.delete` (уже соблюдено в `GoszakupTenderWriter.rebuildLots`).
- Bash-sandbox блокирует localhost:5432 → все `./gradlew` и `psql` запускать с `dangerouslyDisableSandbox: true`. Перед тестами глушить `:8080`: `lsof -ti :8080 | xargs kill -9`.
- Гейт зелёного бэка: `./gradlew test` — 0 падений. Гейт фронта: `cd frontend && npm run build`.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Работаем на ветке `feature/goszakup-orgbin-import` (уже создана, спека закоммичена).
- `psql`: `/Library/PostgreSQL/17/bin/psql`, `PGPASSWORD=admin`, user `postgres`, БД `nirdb`.

## File Structure

**Backend — создать:**
- `src/main/resources/db/migration/V13__facility_hospital_registry.sql` — колонки `region`/`monitor_tenders` + сид 29 больниц ЗКО.

**Backend — изменить:**
- `entity/Facility.java` — +`region`, +`monitorTenders`.
- `dto/request/FacilityRequest.java`, `dto/response/FacilityResponse.java` — +`region`, +`monitorTenders`.
- `repository/FacilityRepository.java` — 2 finder-метода.
- `integration/goszakup/MedicalRelevanceFilter.java` — расширить POSITIVE/NEGATIVE, стеммить.
- `integration/goszakup/GoszakupClient.java` — +`fetchTrdBuyPageByOrgBin`.
- `integration/goszakup/GoszakupHttpClient.java` — реализация + рефактор `postTrdBuyV3`.
- `integration/goszakup/ImportSummary.java` — +`orgsTotal`/`orgsProcessed`/`currentOrgName`.
- `integration/goszakup/GoszakupImportService.java` — путь `importByRegistry`; убрать keyword/whole-feed/kato.

**Backend — тесты:**
- `test/.../goszakup/FakeGoszakupClient.java` — +`fetchTrdBuyPageByOrgBin` + builder `orgPage`.
- `test/.../goszakup/MedicalRelevanceFilterTest.java` — golden-кейсы.
- `test/.../goszakup/GoszakupImportServiceTest.java` — переписать под orgBin.
- `test/.../goszakup/GoszakupImportRelevanceTest.java` — удалить (ступень-1 keyword выпилена).

**Frontend — создать:**
- `frontend/src/app/shared/kz-regions.ts` — общий `KZ_REGIONS`.

**Frontend — изменить:**
- `frontend/src/app/pages/facilities/facilities.component.ts` — KZ-поля (БИН/регион/мониторинг).
- `frontend/src/app/pages/tenders/tenders.component.ts` — прогресс по больницам; переход на `KZ_REGIONS`.

---

## Task 1: MedicalRelevanceFilter — расширить и стеммить

Чистая функция классификации лота. Никаких зависимостей. Дополняем POSITIVE наблюдёнными на живом goszakup терминами и стеммим падежные окончания (лот «Облучатель» в имени объявления встречается как «облучателя»).

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java`
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java`

**Interfaces:**
- Produces: `static boolean isMedicalGoods(String text)`, `static boolean isRelevant(String announcementName, List<String> lotTexts)` (сигнатуры не меняются).

- [ ] **Step 1: Добавить golden-тесты (реальные лоты ЗКО) — упадут**

В `MedicalRelevanceFilterTest.java` добавить метод:

```java
    @Test
    void keepsRealZkoDevicesAndDropsMedicinesFoodHousehold() {
        // KEEP — реальная техника из лент больниц ЗКО (живой goszakup 2026-07-15)
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Облучатель")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Облучателя бактерицидного")).isTrue(); // родит. падеж
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Весы медицинские напольные")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Холодильник медицинский без морозильной камеры")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Концентратор кислорода")).isTrue();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Кровать функциональная механическая")).isTrue();
        // DROP — лекарства/еда/хозтовары/услуги (тоже реальные лоты этих больниц)
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Йодид калия (йодистый калий)")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Сульфадиазин")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Помидор")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Мыло туалетное")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Стартер для дизельного генератора")).isFalse();
        assertThat(MedicalRelevanceFilter.isMedicalGoods("Поверка средств измерений медицинского оборудования")).isFalse();
    }
```

- [ ] **Step 2: Прогнать — часть падает**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.MedicalRelevanceFilterTest'` (dangerouslyDisableSandbox: true)
Expected: FAIL — «Весы медицинские», «Холодильник медицинский», «Концентратор кислорода», «Кровать функциональная», «Облучателя бактерицидного» и «Поверка …» не проходят.

- [ ] **Step 3: Обновить списки в MedicalRelevanceFilter.java**

Заменить блок `POSITIVE` (строки ~15–26) на (добавлены device/расходка-термины; `облучатель`→`облучател`, добавлены `весы медицин`, `холодильник медицин`, `концентратор кислород`, `кроват функционал`, `рециркулятор`, `бактерицидн`, `ингалятор`, `небулайзер`, `пульсоксиметр`, `негатоскоп`, `дозатор`, `кушетк`):

```java
    /** Медицинский товар: виды МИ + расходка/изделия. Термины — стемами, чтобы падежи/мн.ч. матчились. */
    private static final List<String> POSITIVE = List.of(
            "узи", "ультразвук", "эхокардиограф", "рентген", "флюорограф", "маммограф", "ангиограф",
            "томограф", "мрт", "ивл", "вентиляц", "наркоз", "анестезиолог",
            "анализатор", "гематологич", "биохимич", "коагулометр", "центрифуг", "микроскоп",
            "стерилизатор", "автоклав", "эндоскоп", "гастроскоп", "колоноскоп", "бронхоскоп", "лапароскоп",
            "дефибрил", "монитор пациента", "прикроватн монитор", "кардиограф", "электрокардиограф", "экг",
            "спирометр", "инкубатор", "облучател", "рециркулятор", "бактерицидн", "физиотерап",
            "электрофорез", "магнитотерап", "отсасыватель", "аспиратор", "оксиметр", "пульсоксиметр",
            "тонометр", "глюкометр", "коагулятор", "ингалятор", "небулайзер", "негатоскоп", "дозатор",
            "концентратор кислород", "кислородн концентратор", "весы медицин", "холодильник медицин",
            "кроват функционал", "кушетк медицин",
            "стоматологическ", "дентальн", "хирургическ", "операционн стол", "операционн светильник",
            "перчат", "шприц", "катетер", "зонд медицин", "бинт", "пластыр", "электрод", "реагент",
            "тест-систем", "изделие медицинск", "изделия медицинск", "медицинского назначения",
            "расходн материал", "имплант", "протез", "шовн материал", "игл", "скальпел", "пробирк");
```

Заменить блок `NEGATIVE` (строки ~29–32) на (добавлены `поверк`, `метролог`):

```java
    /** Услуга/работы/не-медицина — лот НЕ товар. */
    private static final List<String> NEGATIVE = List.of(
            "услуг", "работы по", "обучен", "осмотр", "утилизац", "удаление", "отход", "ремонт",
            "монтаж", "обслуживан", "замер", "аренд", "страхован", "пошив", "стирк", "поверк", "метролог",
            "летательн", "беспилотн", "дрон", "потолок");
```

- [ ] **Step 4: Прогнать — зелено**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.MedicalRelevanceFilterTest'` (dangerouslyDisableSandbox: true)
Expected: PASS (все методы, включая старые).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilter.java src/test/java/com/vladoose/nir/integration/goszakup/MedicalRelevanceFilterTest.java && git commit -m "$(cat <<'EOF'
feat(goszakup): расширить лот-классификатор медтоваров под живые данные ЗКО

Стеммить падежи (облучателя), +весы/холодильник медицинские, концентратор
кислорода, кровать функциональная; поверка/метрология → услуга.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Миграция V13 — колонки facility + сид 29 больниц ЗКО

Добавляем `region` и `monitor_tenders`, сеем 29 больниц ЗКО как KZ-учреждения. БИН — в существующем `inn` (VARCHAR(12), казахстанский БИН ровно 12 цифр).

**Files:**
- Create: `src/main/resources/db/migration/V13__facility_hospital_registry.sql`
- Test: `src/test/java/com/vladoose/nir/integration/facility/FacilityRegistrySeedTest.java`

**Interfaces:**
- Produces: колонки `facility.region VARCHAR(100)`, `facility.monitor_tenders BOOLEAN NOT NULL DEFAULT false`; 29 строк рынка KZ, `monitor_tenders=true`, `region='Западно-Казахстанская область'`.

- [ ] **Step 1: Написать тест сида (native SQL) — упадёт (нет колонки/данных)**

Create `src/test/java/com/vladoose/nir/integration/facility/FacilityRegistrySeedTest.java`:

```java
package com.vladoose.nir.integration.facility;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FacilityRegistrySeedTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void v13_seeds29ZkoHospitals_withBinAndMonitorFlag() {
        Integer monitored = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE market='KZ' AND monitor_tenders = true " +
                "AND region = 'Западно-Казахстанская область'", Integer.class);
        assertThat(monitored).isGreaterThanOrEqualTo(29);

        // БИН лежит в inn и это 12 цифр
        Integer bins = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE market='KZ' AND monitor_tenders = true " +
                "AND inn ~ '^[0-9]{12}$'", Integer.class);
        assertThat(bins).isGreaterThanOrEqualTo(29);

        // конкретная больница из списка присутствует
        Integer detsk = jdbc.queryForObject(
                "SELECT count(*) FROM facility WHERE inn = '110340002524' AND market='KZ'", Integer.class);
        assertThat(detsk).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Прогнать — падает**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.facility.FacilityRegistrySeedTest'` (dangerouslyDisableSandbox: true)
Expected: FAIL — колонки `monitor_tenders` нет (`column ... does not exist`).

- [ ] **Step 3: Создать миграцию V13**

Create `src/main/resources/db/migration/V13__facility_hospital_registry.sql`:

```sql
-- Реестр больниц для orgBin-импорта goszakup: регион учреждения + флаг мониторинга тендеров.
-- БИН хранится в существующем facility.inn (VARCHAR(12) — казахстанский БИН ровно 12 цифр).
ALTER TABLE facility ADD COLUMN IF NOT EXISTS region VARCHAR(100);
ALTER TABLE facility ADD COLUMN IF NOT EXISTS monitor_tenders BOOLEAN NOT NULL DEFAULT false;

-- Сид: 29 больниц ЗКО (список предоставлен оператором). Регион поставки — ЗКО, рынок KZ,
-- мониторинг включён. Контакты — где были даны. Идемпотентно по уникальному name.
INSERT INTO facility (name, inn, address, phone, email, region, market, monitor_tenders) VALUES
('ГКП «Городская поликлиника №2» на ПХВ УЗ акимата ЗКО','990340004621','ЗКО, г. Уральск, ул. С. Датова, 6','28-43-26','zko-pol2@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «2-больница Казталовского района» УЗ акимата ЗКО','070740006294',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «2-больница района Бәйтерек» УЗ акимата ЗКО','070740002727',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Казталовская районная больница» УЗ акимата ЗКО','070640005371',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной центр психического здоровья» УЗ акимата ЗКО','941240000966',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областная многопрофильная больница» УЗ акимата ЗКО','990340004949','ЗКО, г. Уральск, ул. Н. Савичева, 85','+7 (112) 26-62-70','zkoblbolnica@yandex.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной центр фтизиопульмонологии» УЗ акимата ЗКО','000340002869','ЗКО, г. Уральск, ул. С. Тюленина, 51','87112212947','tubzakup@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областная детская многопрофильная больница» УЗ акимата ЗКО','110340002524','ЗКО, г. Уральск, п. Зачаганск, ул. Х. Доспановой, 2/1','8 7112 50 10 88','odmb.buh@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областная инфекционная больница» УЗ акимата ЗКО','950240000896','ЗКО, г. Уральск, ул. Курмангалиева, 42Н','8 (7112) 21 07 40','zko_infek@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной центр по профилактике ВИЧ-инфекции» УЗ акимата ЗКО','930540000111','ЗКО, г. Уральск, ул. проф. В. Иванова, 42А','8 7112 260561','zkoaids.info@gmail.com','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Акжаикская районная больница» УЗ акимата ЗКО','070740006145',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Городская поликлиника №4» УЗ акимата ЗКО','990340004126',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Бурлинская районная больница» УЗ акимата ЗКО','110240019426',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Городская многопрофильная больница» УЗ акимата ЗКО','160940014836','ЗКО, г. Уральск, мкр Астана, стр. 16','87112933745','gmb.uralsk@gmail.com','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Городская поликлиника №1» УЗ акимата ЗКО','990240004102','ЗКО, г. Уральск, пр. Евразия, стр. 35',NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Городская поликлиника №3» УЗ акимата ЗКО','990340004413','ЗКО, г. Уральск, ул. Александра Карева, 22',NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областная станция скорой медицинской помощи» УЗ акимата ЗКО','000440002604',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной кардиологический центр» УЗ акимата ЗКО','090140003390','ЗКО, г. Уральск, п. Деркул','87028364979','kardio-zko@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной кожно-венерологический диспансер» УЗ акимата ЗКО','000440002436','ЗКО, г. Уральск, пр. Н. Назарбаев, 127',NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной онкологический диспансер» УЗ акимата ЗКО','090140001087','ЗКО, г. Уральск, ул. Алма-Атинская, 58','87112505234','zkood@mail.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной перинатальный центр» УЗ акимата ЗКО','090140001007',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Таскалинская районная больница» УЗ акимата ЗКО','050140005667','ЗКО, Таскалинский район, с. Таскала, ул. Абая, 37',NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Жангалинская районная больница» УЗ акимата ЗКО','070440006283',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областное патологоанатомическое бюро» УЗ акимата ЗКО','030540002920','ЗКО, г. Уральск, ул. Мухита, 1А','8-7112-26-65-17','zko-anatom@yandex.ru','Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Областной центр крови» УЗ акимата ЗКО','000240002825','ЗКО, г. Уральск, ул. А. Молдагуловой, 2','87112518033',NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Жанибекская районная больница» УЗ акимата ЗКО','070640005252',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «2-больница Теректинского района» УЗ акимата ЗКО','070740006284',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Ауданаралық сауықтыру ауруханасы оңалту орталығы» УЗ акимата ЗКО','091140010683',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true),
('ГКП на ПХВ «Теректинская районная больница» УЗ акимата ЗКО','070740006254',NULL,NULL,NULL,'Западно-Казахстанская область','KZ',true)
ON CONFLICT (name) DO NOTHING;
```

- [ ] **Step 4: Прогнать — зелено (Flyway накатит V13)**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.facility.FacilityRegistrySeedTest'` (dangerouslyDisableSandbox: true)
Expected: PASS. (Если упало на «Flyway checksum/validate» — БД уже имеет запись о V13 из прошлого прогона; для чистого прогона пересоздать nirdb по §5/§10 CLAUDE.md или `./gradlew flywayRepair` если настроен.)

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V13__facility_hospital_registry.sql src/test/java/com/vladoose/nir/integration/facility/FacilityRegistrySeedTest.java && git commit -m "$(cat <<'EOF'
feat(db): V13 — реестр больниц (region/monitor_tenders) + сид 29 ЗКО

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Facility — сущность, DTO, маппер, репозиторий

**Files:**
- Modify: `src/main/java/com/vladoose/nir/entity/Facility.java`
- Modify: `src/main/java/com/vladoose/nir/dto/request/FacilityRequest.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/FacilityResponse.java`
- Modify: `src/main/java/com/vladoose/nir/repository/FacilityRepository.java`
- Test: `src/test/java/com/vladoose/nir/integration/facility/FacilityRegistryRepositoryTest.java`

**Interfaces:**
- Consumes: колонки V13 (Task 2).
- Produces:
  - `Facility.getRegion()/setRegion(String)`, `Facility.isMonitorTenders()/setMonitorTenders(boolean)`.
  - `FacilityRepository.findByMarketAndMonitorTendersTrue(Market market) : List<Facility>`
  - `FacilityRepository.findByMarketAndRegionAndMonitorTendersTrue(Market market, String region) : List<Facility>`

- [ ] **Step 1: Написать тест репозитория — упадёт (методов нет)**

Create `src/test/java/com/vladoose/nir/integration/facility/FacilityRegistryRepositoryTest.java`:

```java
package com.vladoose.nir.integration.facility;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FacilityRegistryRepositoryTest {

    @Autowired FacilityRepository repo;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void findsMonitoredZkoHospitalsFromSeed() {
        List<Facility> zko = repo.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, "Западно-Казахстанская область");
        assertThat(zko).hasSizeGreaterThanOrEqualTo(29);
        assertThat(zko).allMatch(Facility::isMonitorTenders);
        assertThat(zko).anyMatch(f -> "110340002524".equals(f.getInn()));
    }

    @Test
    void allMonitoredKzIncludesSeed_butRegionFilterNarrows() {
        assertThat(repo.findByMarketAndMonitorTendersTrue(Market.KZ)).hasSizeGreaterThanOrEqualTo(29);
        assertThat(repo.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, "Карагандинская область")).isEmpty();
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция)**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.facility.FacilityRegistryRepositoryTest'` (dangerouslyDisableSandbox: true)
Expected: FAIL — метод `findByMarketAndMonitorTendersTrue` не существует (ошибка компиляции теста).

- [ ] **Step 3: Добавить поля в Facility**

В `entity/Facility.java` после поля `email` (перед `market`) добавить:

```java
    @Column(length = 100)
    private String region;

    /** true — тянуть тендеры этой организации по orgBin (goszakup). Только для KZ-больниц. */
    @Column(name = "monitor_tenders", nullable = false)
    private boolean monitorTenders;
```

- [ ] **Step 4: Добавить поля в DTO**

В `dto/request/FacilityRequest.java` внутри класса добавить:

```java
    private String region;
    private boolean monitorTenders;
```

В `dto/response/FacilityResponse.java` внутри класса добавить:

```java
    private String region;
    private boolean monitorTenders;
```

(MapStruct-маппер `FacilityMapper` менять НЕ нужно — поля мапятся по имени автоматически.)

- [ ] **Step 5: Добавить finder-методы в репозиторий**

Заменить `repository/FacilityRepository.java` целиком:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    List<Facility> findByMarketAndMonitorTendersTrue(Market market);
    List<Facility> findByMarketAndRegionAndMonitorTendersTrue(Market market, String region);
}
```

- [ ] **Step 6: Прогнать — зелено**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.facility.FacilityRegistryRepositoryTest'` (dangerouslyDisableSandbox: true)
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/entity/Facility.java src/main/java/com/vladoose/nir/dto/request/FacilityRequest.java src/main/java/com/vladoose/nir/dto/response/FacilityResponse.java src/main/java/com/vladoose/nir/repository/FacilityRepository.java src/test/java/com/vladoose/nir/integration/facility/FacilityRegistryRepositoryTest.java && git commit -m "$(cat <<'EOF'
feat(facility): region + monitorTenders (реестр больниц для orgBin-импорта)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Клиент goszakup — fetchTrdBuyPageByOrgBin + фейк

Добавляем v3-фильтр по `orgBin` (проверен живьём — валидное поле). Общий постинг v3 выносим в `postTrdBuyV3`, чтобы `...ByKato` и `...ByOrgBin` не дублировали разбор ответа.

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupClient.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java`
- Modify: `src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java`

**Interfaces:**
- Produces: `GoszakupClient.fetchTrdBuyPageByOrgBin(String orgBin, Long after) : TrdBuyV3PageDto`.
- Produces (fake): `FakeGoszakupClient.orgPage(String orgBin, TrdBuyDto... items)`, поле `List<String> orgBinsQueried`.

- [ ] **Step 1: Объявить метод в интерфейсе**

В `GoszakupClient.java` после строки с `fetchTrdBuyPageByKato` добавить:

```java
    /** v3 GraphQL: лента одной организации по её БИН (orgBin). after == null → первая страница. */
    TrdBuyV3PageDto fetchTrdBuyPageByOrgBin(String orgBin, Long after);
```

- [ ] **Step 2: Реализовать в GoszakupHttpClient + рефактор общего постинга**

В `GoszakupHttpClient.java` заменить метод `fetchTrdBuyPageByKato` (строки ~55–91) на три члена — константу полей, kato-метод и orgBin-метод, плюс общий `postTrdBuyV3`:

```java
    private static final String V3_FIELDS =
            "id number_anno:numberAnno name_ru:nameRu total_sum:totalSum "
          + "ref_buy_status_id:refBuyStatusId customer_bin:customerBin org_bin:orgBin "
          + "publish_date:publishDate end_date:endDate system_id:systemId";

    @Override
    public TrdBuyV3PageDto fetchTrdBuyPageByKato(List<String> katoCodes, Long after) {
        // v3 GraphQL: серверно сузить ленту до региона (фильтр kato — массив точных 9-значных кодов).
        String query = "query($k:[String],$l:Int,$a:Int){ TrdBuy(filter:{kato:$k}, limit:$l, after:$a){ "
                + V3_FIELDS + " } }";
        ObjectNode vars = objectMapper.createObjectNode();
        vars.set("k", objectMapper.valueToTree(katoCodes));
        vars.put("l", pageSize);
        if (after != null) vars.put("a", after);
        return postTrdBuyV3(query, vars);
    }

    @Override
    public TrdBuyV3PageDto fetchTrdBuyPageByOrgBin(String orgBin, Long after) {
        // v3 GraphQL: лента одной организации-заказчика по её БИН (orgBin — валидный фильтр TrdBuy).
        String query = "query($o:String,$l:Int,$a:Int){ TrdBuy(filter:{orgBin:$o}, limit:$l, after:$a){ "
                + V3_FIELDS + " } }";
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("o", orgBin);
        vars.put("l", pageSize);
        if (after != null) vars.put("a", after);
        return postTrdBuyV3(query, vars);
    }

    /** Общий POST v3-запроса TrdBuy: тело, разбор items, вычисление nextAfter из pageInfo. */
    private TrdBuyV3PageDto postTrdBuyV3(String query, ObjectNode vars) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);
            JsonNode root = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (root.path("errors").size() > 0) {
                throw new IllegalStateException("goszakup v3 GraphQL: " + root.get("errors"));
            }
            List<TrdBuyDto> items = new java.util.ArrayList<>();
            for (JsonNode n : root.path("data").path("TrdBuy")) {
                items.add(objectMapper.treeToValue(n, TrdBuyDto.class));
            }
            JsonNode pageInfo = root.path("extensions").path("pageInfo");
            Long nextAfter = (!items.isEmpty() && pageInfo.path("hasNextPage").asBoolean(false))
                    ? pageInfo.path("lastId").asLong() : null;
            TrdBuyV3PageDto page = new TrdBuyV3PageDto();
            page.setItems(items);
            page.setNextAfter(nextAfter);
            return page;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("goszakup v3: разбор JSON: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 3: Реализовать в FakeGoszakupClient + builder**

В `FakeGoszakupClient.java` после поля `v3Pages` (строка ~26) добавить:

```java
    /** orgBin → одностраничная лента организации. */
    public final Map<String, TrdBuyV3PageDto> orgPages = new HashMap<>();
    public final List<String> orgBinsQueried = new ArrayList<>();
```

После метода `fetchTrdBuyPageByKato` (строка ~45) добавить:

```java
    @Override public TrdBuyV3PageDto fetchTrdBuyPageByOrgBin(String orgBin, Long after) {
        orgBinsQueried.add(orgBin);
        TrdBuyV3PageDto p = (after == null) ? orgPages.get(orgBin) : null;
        if (p != null) return p;
        TrdBuyV3PageDto empty = new TrdBuyV3PageDto(); empty.setItems(new ArrayList<>()); return empty;
    }
```

В секции builders (после `v3Page`, строка ~89) добавить:

```java
    public TrdBuyV3PageDto orgPage(String orgBin, TrdBuyDto... items) {
        TrdBuyV3PageDto p = new TrdBuyV3PageDto();
        p.setItems(new ArrayList<>(List.of(items))); p.setNextAfter(null);
        orgPages.put(orgBin, p); return p;
    }
```

- [ ] **Step 4: Гейт — компиляция + существующие goszakup-тесты не сломались рефактором**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.GoszakupHttpClientTest' --tests 'com.vladoose.nir.integration.goszakup.KatoDictionaryTest' --tests 'com.vladoose.nir.integration.goszakup.GoszakupDtoJsonTest'` (dangerouslyDisableSandbox: true)
Expected: PASS (рефактор `postTrdBuyV3` не изменил поведение kato-пути).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/goszakup/GoszakupClient.java src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java src/test/java/com/vladoose/nir/integration/goszakup/FakeGoszakupClient.java && git commit -m "$(cat <<'EOF'
feat(goszakup): v3-фильтр TrdBuy по orgBin + общий postTrdBuyV3

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Сервис импорта — путь importByRegistry (ядро)

Переводим `fillImport` на перебор мониторимых учреждений по `orgBin`, убираем keyword-предфильтр и whole-feed/kato-пути. Прогресс — по больницам.

**Files:**
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`
- Rewrite: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java`
- Delete: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportRelevanceTest.java`

**Interfaces:**
- Consumes: `FacilityRepository.findByMarketAndMonitorTendersTrue`/`...AndRegion...` (Task 3), `GoszakupClient.fetchTrdBuyPageByOrgBin` (Task 4), `MedicalRelevanceFilter.isRelevant` (Task 1).
- Produces: `ImportSummary` +`orgsTotal`/`orgsProcessed`/`currentOrgName`; `GoszakupImportService(GoszakupClient, GoszakupTenderWriter, FacilityRepository, String statusesCsv, int sinceDays, int maxPages)`; `fillImport(String region, ImportSummary)` (сигнатура прежняя — scheduler не трогаем).

- [ ] **Step 1: Добавить прогресс-поля в ImportSummary**

В `ImportSummary.java` перед `private String message;` добавить:

```java
    /** Прогресс orgBin-импорта: больниц обработано / всего в реестре, текущая. */
    private int orgsTotal;
    private int orgsProcessed;
    private String currentOrgName;
```

- [ ] **Step 2: Переписать GoszakupImportServiceTest под orgBin — упадёт**

Заменить `GoszakupImportServiceTest.java` целиком:

```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GoszakupImportServiceTest {

    // Тесты работают в регионе, которого НЕТ в сиде V13 (ЗКО), чтобы сеяные больницы не мешали счётчикам.
    private static final String REGION = "Карагандинская область";

    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired RegionResolver regionResolver;

    FakeGoszakupClient fake;
    GoszakupTenderWriter writer;
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        writer = new GoszakupTenderWriter(tenderRepository, regionResolver);
        service = svc("", 3650);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private GoszakupImportService svc(String statuses, int sinceDays) {
        return new GoszakupImportService(fake, writer, facilityRepository, statuses, sinceDays, 20);
    }

    /** Мониторимая KZ-больница в тестовом регионе с заданным БИН. */
    private Facility hospital(String name, String bin) {
        Facility f = new Facility();
        f.setName(name); f.setInn(bin); f.setRegion(REGION);
        f.setMonitorTenders(true); f.setMarket(Market.KZ);
        return facilityRepository.save(f);
    }

    private static LotDto lot(String name, String descr) {
        LotDto l = new LotDto();
        l.setLotNumber("1"); l.setNameRu(name); l.setDescriptionRu(descr);
        l.setAmount(new BigDecimal("6000000")); l.setCount(1);
        return l;
    }

    @Test
    void createsKzPublicTender_fromOrgBinFeed() {
        hospital("Больница А", "BIN1");
        fake.orgPage("BIN1", FakeGoszakupClient.buy("100-1", "Приобретение изделий", 230, "BIN1",
                "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ портативный", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getOrgsTotal()).isEqualTo(1);
        assertThat(s.getOrgsProcessed()).isEqualTo(1);
        assertThat(s.getFetched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(fake.orgBinsQueried).contains("BIN1");
        Tender t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.getRegion()).isEqualTo(REGION);            // регион из реестра, не от заказчика
        assertThat(t.getFacility()).isNull();
    }

    @Test
    void dropsNonMedicalLots_evenFromMonitoredHospital() {
        hospital("Больница Б", "BIN2");
        fake.orgPage("BIN2",
                FakeGoszakupClient.buy("MED-1", "Государственный закупки медицинских изделий", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("FOOD-1", "Приобретение продуктов питания", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("MED-1", List.of(lot("Облучатель бактерицидный", null)));
        fake.lotsByAnno.put("FOOD-1", List.of(lot("Помидор", null), lot("Молоко натуральное", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("MED-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("FOOD-1")).isEmpty();
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        hospital("Больница В", "BIN3");
        fake.orgPage("BIN3", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN3", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат ИВЛ", null)));
        service.importMedicalTenders(REGION);

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findBySourceExtId("100-1")).isEmpty();
    }

    @Test
    void idempotent_secondRunUpdatesNotDuplicates() {
        hospital("Больница Г", "BIN4");
        fake.orgPage("BIN4", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN4", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ портативный", null)));

        assertThat(service.importMedicalTenders(REGION).getCreated()).isEqualTo(1);
        fake.orgPages.get("BIN4").getItems().get(0).setTotalSum(new BigDecimal("9999999"));
        ImportSummary second = svc("", 3650).importMedicalTenders(REGION);

        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);
        assertThat(tenderRepository.findAll().stream().filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("100-1").orElseThrow().getTotalCost()).isEqualByComparingTo("9999999");
    }

    @Test
    void regionFromRegistry_overridesResolvedCustomerRegion() {
        // республиканский заказчик (юрадрес Астана), но больница в реестре ЗКО-региона теста → регион = реестровый
        hospital("Больница Д", "BINAST");
        fake.orgPage("BINAST", FakeGoszakupClient.buy("Z-3", "Закуп", 230, "BINAST", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("Z-3", List.of(lot("Аппарат ИВЛ", null)));
        SubjectDto subj = new SubjectDto(); subj.setBin("BINAST"); subj.setNameRu("РГКП «Центр» г. Астана");
        fake.subjectsByBin.put("BINAST", subj);

        service.importMedicalTenders(REGION);

        Tender t = tenderRepository.findBySourceExtId("Z-3").orElseThrow();
        assertThat(t.getRegion()).isEqualTo(REGION);
        assertThat(t.getCustomerName()).contains("Центр");   // заказчик сохранён
    }

    @Test
    void skipsOld_bySinceDays() {
        hospital("Больница Е", "BIN5");
        String recent = java.time.LocalDate.now().minusDays(5) + "T00:00:00";
        String old = java.time.LocalDate.now().minusDays(400) + "T00:00:00";
        fake.orgPage("BIN5",
                FakeGoszakupClient.buy("NEW-1", "Закуп", 230, "BIN5", recent, recent),
                FakeGoszakupClient.buy("OLD-1", "Закуп", 230, "BIN5", old, old));
        fake.lotsByAnno.put("NEW-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("OLD-1", List.of(lot("Аппарат УЗИ", null)));

        svc("", 30).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty();
    }

    @Test
    void itemError_isCountedAndDoesNotAbortRun() {
        hospital("Больница Ж", "BIN6");
        fake.orgPage("BIN6",
                FakeGoszakupClient.buy("OK-1", "Закуп", 230, "BINOK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("ERR-1", "Закуп", 230, "BINERR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("OK-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("ERR-1", List.of(lot("Аппарат УЗИ", null)));
        fake.failingSubjectBins.add("BINERR");

        ImportSummary s = svc("", 3650).importMedicalTenders(REGION);

        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("ERR-1")).isEmpty();
    }

    @Test
    void lotDescriptionRu_savedAsRequiredSpec() {
        hospital("Больница З", "BIN7");
        fake.orgPage("BIN7", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN7", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ", "УЗИ экспертного класса, не менее 3 датчиков, доплер")));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("100-1").orElseThrow().getLots().get(0).getRequiredSpec())
                .isEqualTo("УЗИ экспертного класса, не менее 3 датчиков, доплер");
    }

    @Test
    void skipsNonCurrentSystemId() {
        hospital("Больница И", "BIN8");
        var legacy = FakeGoszakupClient.buy("LEG-2", "Закуп", 230, "BIN8", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        legacy.setSystemId(2);
        var current = FakeGoszakupClient.buy("CUR-3", "Закуп", 230, "BIN8", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        current.setSystemId(3);
        fake.orgPage("BIN8", legacy, current);
        fake.lotsByAnno.put("LEG-2", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("CUR-3", List.of(lot("Аппарат УЗИ", null)));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("CUR-3")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("LEG-2")).isEmpty();
    }

    @Test
    void expiredDeadline_overridesPortalActiveStatus() {
        hospital("Больница К", "BIN9");
        String past = java.time.LocalDate.now().minusDays(2) + "T00:00:00";
        fake.orgPage("BIN9", FakeGoszakupClient.buy("EXP-1", "Закуп", 220, "BIN9", past, past));
        fake.lotsByAnno.put("EXP-1", List.of(lot("Аппарат УЗИ", null)));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("EXP-1").orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void emptyRegistryForRegion_returnsMessageWithoutFetching() {
        ImportSummary s = service.importMedicalTenders(REGION); // ни одной больницы не создали

        assertThat(s.isEnabled()).isFalse();
        assertThat(s.getMessage()).contains(REGION);
        assertThat(s.getOrgsTotal()).isZero();
        assertThat(fake.orgBinsQueried).isEmpty();
    }

    @Test
    void processesMultipleHospitals_countingOrgs() {
        hospital("Больница Л", "BIN10");
        hospital("Больница М", "BIN11");
        fake.orgPage("BIN10", FakeGoszakupClient.buy("A-1", "Закуп", 230, "BIN10", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.orgPage("BIN11", FakeGoszakupClient.buy("B-1", "Закуп", 230, "BIN11", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("A-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("B-1", List.of(lot("Аппарат ИВЛ", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getOrgsTotal()).isEqualTo(2);
        assertThat(s.getOrgsProcessed()).isEqualTo(2);
        assertThat(s.getCreated()).isEqualTo(2);
        assertThat(fake.orgBinsQueried).contains("BIN10", "BIN11");
    }
}
```

- [ ] **Step 3: Удалить устаревший GoszakupImportRelevanceTest**

Ступень-1 keyword-предфильтр выпилена; его стадия-2 покрыта `MedicalRelevanceFilterTest`.

```bash
cd /Users/vlad/IdeaProjects/AIS && git rm src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportRelevanceTest.java
```

- [ ] **Step 4: Прогнать — падает (сервис ещё старый)**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest'` (dangerouslyDisableSandbox: true)
Expected: FAIL — конструктор `GoszakupImportService(fake, writer, facilityRepository, ...)` не существует / нет `fetchTrdBuyPageByOrgBin`-пути.

- [ ] **Step 5: Переписать GoszakupImportService**

Заменить `GoszakupImportService.java` целиком:

```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.FacilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Импорт KZ-тендеров goszakup по РЕЕСТРУ больниц: перебирает мониторимые учреждения (facility,
 * рынок KZ, monitor_tenders=true) выбранного региона, по каждому дёргает v3 TrdBuy(orgBin),
 * фетчит лоты и апсертит через GoszakupTenderWriter. «Медтовар» решается по ЛОТАМ, не по имени.
 * Сам НЕ транзакционный: сетевой I/O не держит БД-коннект; ошибка одной больницы/тендера идёт в
 * ImportSummary.errors и не валит прогон.
 */
@Service
public class GoszakupImportService {

    private static final Logger log = LoggerFactory.getLogger(GoszakupImportService.class);

    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final FacilityRepository facilityRepository;
    private final Set<Integer> statuses;
    private final int sinceDays;
    private final int maxPages;

    public GoszakupImportService(GoszakupClient client,
                                 GoszakupTenderWriter writer,
                                 FacilityRepository facilityRepository,
                                 @Value("${goszakup.import.statuses:}") String statusesCsv,
                                 @Value("${goszakup.import.since-days:30}") int sinceDays,
                                 @Value("${goszakup.import.max-pages:60}") int maxPages) {
        this.client = client;
        this.writer = writer;
        this.facilityRepository = facilityRepository;
        this.statuses = parseStatuses(statusesCsv);
        this.sinceDays = sinceDays;
        this.maxPages = maxPages;
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    /** Лояльный разбор статусов: нечисловые токены пропускаем, чтобы кривой конфиг не ронял старт. */
    private static Set<Integer> parseStatuses(String s) {
        Set<Integer> ids = new HashSet<>();
        for (String token : csv(s)) {
            try { ids.add(Integer.valueOf(token)); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return ids;
    }

    public ImportSummary importMedicalTenders() {
        return importMedicalTenders(null);
    }

    /** region — каноническое имя области/города (как в фильтре UI) или null: все мониторимые больницы KZ. */
    public ImportSummary importMedicalTenders(String region) {
        ImportSummary sum = new ImportSummary();
        fillImport(region, sum);
        return sum;
    }

    /** Наполняет ПЕРЕДАННЫЙ summary по ходу работы — вызывающий может показывать живой прогресс. */
    public void fillImport(String region, ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        List<Facility> orgs = (region == null || region.isBlank())
                ? facilityRepository.findByMarketAndMonitorTendersTrue(Market.KZ)
                : facilityRepository.findByMarketAndRegionAndMonitorTendersTrue(Market.KZ, region.trim());
        orgs = orgs.stream().filter(f -> f.getInn() != null && !f.getInn().isBlank()).toList();
        sum.setOrgsTotal(orgs.size());
        if (orgs.isEmpty()) {
            sum.setEnabled(false);
            sum.setMessage(region == null || region.isBlank()
                    ? "В реестре нет учреждений с мониторингом тендеров (KZ)"
                    : "В реестре нет учреждений с мониторингом тендеров для региона: " + region);
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(sinceDays);
        for (Facility org : orgs) {
            sum.setCurrentOrgName(org.getName());
            try {
                fetchOrgFeed(org.getInn(), org.getRegion(), cutoff, sum);
            } catch (RuntimeException e) {
                sum.setErrors(sum.getErrors() + 1);
                log.warn("goszakup: ошибка импорта по БИН {} ({}): {}", org.getInn(), org.getName(), e.toString());
            }
            sum.setOrgsProcessed(sum.getOrgsProcessed() + 1);
        }
        sum.setMessage(String.format("Больниц %d, получено %d, подходящих %d, создано %d, обновлено %d, ошибок %d",
                sum.getOrgsProcessed(), sum.getFetched(), sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors()));
    }

    private void fetchOrgFeed(String orgBin, String region, LocalDate cutoff, ImportSummary sum) {
        Long after = null;
        int pagesRead = 0;
        do {
            var page = client.fetchTrdBuyPageByOrgBin(orgBin, after);
            List<TrdBuyDto> items = page.getItems() != null ? page.getItems() : List.of();
            processItems(items, cutoff, sum, region);
            pagesRead++;
            if (wholePageOlderThan(items, cutoff)) break;
            after = page.getNextAfter();
        } while (after != null && pagesRead < maxPages);
    }

    private void processItems(List<TrdBuyDto> items, LocalDate cutoff, ImportSummary sum, String regionOverride) {
        for (TrdBuyDto d : items) {
            sum.setFetched(sum.getFetched() + 1);
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub != null && pub.isBefore(cutoff)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            if (!statusOk(d) || !systemOk(d)) { sum.setSkipped(sum.getSkipped() + 1); continue; }
            importOne(d, sum, regionOverride);
        }
    }

    /** Сеть — ВНЕ транзакции; запись — в отдельной per-item транзакции writer'а. Ошибка элемента не валит прогон. */
    private void importOne(TrdBuyDto d, ImportSummary sum, String regionOverride) {
        try {
            SubjectDto subj = client.fetchSubject(d.effectiveBin());
            List<LotDto> lots = client.fetchLots(d.getNumberAnno());
            List<String> lotTexts = lots.stream()
                    .map(l -> ((l.getNameRu() == null ? "" : l.getNameRu()) + " "
                             + (l.getDescriptionRu() == null ? "" : l.getDescriptionRu())).trim())
                    .toList();
            if (!MedicalRelevanceFilter.isRelevant(d.getNameRu(), lotTexts)) {
                sum.setSkipped(sum.getSkipped() + 1); // лоты — не медтовар (лекарства/еда/хозтовары/услуги)
                return;
            }
            GoszakupTenderWriter.Result r = writer.upsertOne(d, subj, lots, regionOverride);
            if (r == GoszakupTenderWriter.Result.CREATED) sum.setCreated(sum.getCreated() + 1);
            else sum.setUpdated(sum.getUpdated() + 1);
            sum.setMatched(sum.getMatched() + 1); // «подходящих» = медтоварные (созданные + обновлённые)
        } catch (RuntimeException e) {
            sum.setErrors(sum.getErrors() + 1);
            log.warn("goszakup: ошибка импорта объявления {}: {}", d.getNumberAnno(), e.toString());
        }
    }

    private static boolean wholePageOlderThan(List<TrdBuyDto> items, LocalDate cutoff) {
        if (items.isEmpty()) return false;
        for (TrdBuyDto d : items) {
            LocalDate pub = GoszakupParse.localDate(d.getPublishDate());
            if (pub == null || !pub.isBefore(cutoff)) return false; // без даты — консервативно продолжаем
        }
        return true;
    }

    private boolean statusOk(TrdBuyDto d) {
        return statuses.isEmpty() || (d.getRefBuyStatusId() != null && statuses.contains(d.getRefBuyStatusId()));
    }
    /** Только текущий модуль госзакупа (system_id=3); null трактуем как «брать» (поле может отсутствовать). */
    private boolean systemOk(TrdBuyDto d) {
        return d.getSystemId() == null || d.getSystemId() == 3;
    }
}
```

- [ ] **Step 6: Прогнать целевые тесты — зелено**

Run: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test --tests 'com.vladoose.nir.integration.goszakup.GoszakupImportServiceTest' --tests 'com.vladoose.nir.integration.goszakup.GoszakupImportSchedulerTest'` (dangerouslyDisableSandbox: true)
Expected: PASS. (Scheduler-тест мокает `fillImport(region, sum)` — сигнатура неизменна, проходит.)

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/integration/goszakup/ImportSummary.java src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java src/test/java/com/vladoose/nir/integration/goszakup/GoszakupImportServiceTest.java && git commit -m "$(cat <<'EOF'
feat(goszakup): импорт по реестру больниц (orgBin), медфильтр по лотам

fillImport перебирает мониторимые facility региона, тянет TrdBuy(orgBin),
классифицирует по лотам; регион тендера — из реестра. Keyword-предфильтр и
whole-feed/kato-путь выпилены. Прогресс по больницам.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Фронт — управление реестром на /facilities + прогресс по больницам

**Files:**
- Create: `frontend/src/app/shared/kz-regions.ts`
- Modify: `frontend/src/app/pages/facilities/facilities.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Produces: `export const KZ_REGIONS: string[]`.

- [ ] **Step 1: Общий список регионов**

Create `frontend/src/app/shared/kz-regions.ts`:

```typescript
// Канонические регионы KZ (значения совпадают с RegionResolver на бэке и фильтром тендеров).
export const KZ_REGIONS: string[] = [
  'г. Астана', 'г. Алматы', 'г. Шымкент',
  'Абайская область', 'Акмолинская область', 'Актюбинская область', 'Алматинская область',
  'Атырауская область', 'Восточно-Казахстанская область', 'Жамбылская область',
  'Жетысуская область', 'Западно-Казахстанская область', 'Карагандинская область',
  'Костанайская область', 'Кызылординская область', 'Мангистауская область',
  'Павлодарская область', 'Северо-Казахстанская область', 'Туркестанская область',
  'Улытауская область'
];
```

- [ ] **Step 2: tenders.component — использовать общий KZ_REGIONS**

В `frontend/src/app/pages/tenders/tenders.component.ts` добавить импорт (рядом с прочими import):

```typescript
import { KZ_REGIONS } from '../../shared/kz-regions';
```

Заменить инлайн-массив `readonly REGIONS: string[] = [ ... ];` (строки ~890–899) на:

```typescript
  readonly REGIONS: string[] = KZ_REGIONS;
```

- [ ] **Step 3: tenders.component — прогресс по больницам**

Заменить строку 74 (goszakup-прогресс) на показ больниц вместо страниц:

```html
            больница {{ importStatus.lastSummary?.orgsProcessed || 0 }}/{{ importStatus.lastSummary?.orgsTotal || '…' }}
```

Заменить метод `importPct()` (строки ~1334–1338) на прогресс по больницам:

```typescript
  importPct(): number {
    const s = this.importStatus?.lastSummary;
    if (!s?.orgsTotal) return 5;
    return Math.max(5, Math.min(100, Math.round(100 * (s.orgsProcessed || 0) / s.orgsTotal)));
  }
```

(СК-Фармация-прогресс на строках 85–93 и `skImportPct()` НЕ трогаем — СК по-прежнему по страницам.)

- [ ] **Step 4: facilities.component — KZ-поля (БИН/регион/мониторинг)**

В `frontend/src/app/pages/facilities/facilities.component.ts`:

(a) Импорты и общий список после существующих import:

```typescript
import { MarketService } from '../../services/market.service';
import { KZ_REGIONS } from '../../shared/kz-regions';
```

(b) В конструктор добавить `public market: MarketService` (после `public auth: AuthService`):

```typescript
  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              public auth: AuthService, public market: MarketService) {
```

(c) В класс добавить хелпер и список (рядом с полями компонента):

```typescript
  readonly REGIONS = KZ_REGIONS;
  isKz(): boolean { return this.market.value === 'KZ'; }
```

(d) В `FormGroup` (строки ~97–106) добавить два контрола (после `email`):

```typescript
    email: new FormControl(''),
    region: new FormControl(''),
    monitorTenders: new FormControl(false)
```

(e) В шаблоне-форме заменить строку метки ИНН (строка 29) на динамический ярлык БИН/ИНН:

```html
      <label>{{ isKz() ? 'БИН' : 'ИНН' }}<input formControlName="inn" [class.input-error]="validationErrors.inn" /><span class="field-error" *ngIf="validationErrors.inn">{{ validationErrors.inn }}</span></label>
```

(f) В форме перед `<div class="form-actions">` (строка 36) добавить KZ-поля:

```html
      <label *ngIf="isKz()">Регион
        <select formControlName="region">
          <option value="">— не выбран —</option>
          <option *ngFor="let r of REGIONS" [value]="r">{{ r }}</option>
        </select>
      </label>
      <label *ngIf="isKz()" class="check">
        <input type="checkbox" formControlName="monitorTenders" /> Мониторить тендеры (goszakup)
      </label>
```

(g) В `<select>` внутри `.edit-form` наследует стиль `input`? Добавить в блок styles правило для select и чекбокса (после `.edit-form input { ... }`, строка ~82):

```css
    .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .edit-form label.check { display: flex; align-items: center; gap: 8px; font-weight: 500; }
    .edit-form label.check input { display: inline-block; width: auto; margin-top: 0; }
    .badge-monitor { display: inline-block; margin-left: 6px; padding: 1px 7px; background: #dbeafe; color: #1e40af; border-radius: 10px; font-size: 11px; vertical-align: middle; }
```

(h) В таблице показать бейдж мониторинга и регион. Заменить строку заголовков (строка 46):

```html
        <tr><th>Название</th><th>{{ isKz() ? 'БИН' : 'ИНН' }}</th><th>Регион</th><th>Адрес</th><th>Контактное лицо</th><th>Телефон</th><th>Эл. почта</th><th *ngIf="auth.isAdmin()">Действия</th></tr>
```

Заменить ячейки названия/ИНН (строка 50) — добавить бейдж и колонку региона:

```html
          <td data-label="Название">{{ f.name }}<span class="badge-monitor" *ngIf="f.monitorTenders">🔔 тендеры</span></td><td data-label="БИН/ИНН">{{ f.inn }}</td><td data-label="Регион">{{ f.region }}</td><td data-label="Адрес">{{ f.address }}</td>
```

- [ ] **Step 5: Гейт — сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` (dangerouslyDisableSandbox: true)
Expected: BUILD SUCCESSFUL, без ошибок бюджета (`anyComponentStyle`).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/shared/kz-regions.ts frontend/src/app/pages/facilities/facilities.component.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "$(cat <<'EOF'
feat(ui): реестр больниц на /facilities (БИН/регион/мониторинг) + прогресс по больницам

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Полный прогон + живая проверка

**Files:** нет (проверочная задача).

- [ ] **Step 1: Полный бэк-прогон**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew test` (dangerouslyDisableSandbox: true)
Expected: BUILD SUCCESSFUL, 0 падений. Если падает не-goszakup тест — чинить в рамках плана (регрессия). Особое внимание: тесты, конструирующие `GoszakupImportService`, и любые, что импортировали `GoszakupImportRelevanceTest`-концепцию.

- [ ] **Step 2: Фронт-сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` (dangerouslyDisableSandbox: true)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Живой прогон (Playwright, рынок KZ, реальный токен)**

Поднять бэк с токеном: `GOSZAKUP_TOKEN="$(cat /tmp/goszakup.token)" JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun` (dangerouslyDisableSandbox: true, фоном) + фронт `cd frontend && npm start`.
Через Playwright MCP:
1. `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`, reload.
2. `/facilities` — убедиться: 29 больниц ЗКО видны, у них бейдж «🔔 тендеры», колонка «Регион» = ЗКО, поле называется «БИН». Открыть одну на редактирование — виден селектор «Регион» и чекбокс «Мониторить тендеры».
3. `/tenders`, фильтр региона → «Западно-Казахстанская область», нажать «Обновить тендеры — Западно-Казахстанская область». Прогресс идёт «больница N/29». Дождаться тоста.
4. Убедиться, что появились медтендеры больниц ЗКО, которых не было (напр. «Облучатель», «Весы медицинские», «Государственный закупки медицинских изделий»), а лекарства/еда/услуги НЕ засорили список.

Проверка счётчика в БД:

```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "SELECT platform, count(*) FROM tender WHERE market='KZ' AND region='Западно-Казахстанская область' GROUP BY 1;"
```
Expected: число `GOSZAKUP`-тендеров ЗКО заметно выросло относительно исходных 2.

- [ ] **Step 4: «Куда смотреть» пользователю**

Дать краткий click-by-click тур (что нажать, что увидеть) — раздел «Учреждения» (реестр больниц) и «Обновить тендеры — ЗКО».

---

## Self-Review (проверка плана против спеки)

- **§3.1 колонки facility** → Task 2 (миграция) + Task 3 (сущность/DTO). ✔
- **§3.2 сид 29 ЗКО** → Task 2. ✔
- **§4.1 fetchTrdBuyPageByOrgBin + postTrdBuyV3** → Task 4. ✔
- **§4.2 репозиторий-finders** → Task 3. ✔
- **§4.3 importByRegistry, регион из реестра, keyword убран** → Task 5. ✔
- **§4.4 дочистка MedicalRelevanceFilter** → Task 1. ✔
- **§4.5 writer без изменений контракта** → используется как есть в Task 5. ✔
- **§4.6 ImportSummary orgs-поля** → Task 5 Step 1. ✔
- **§4.7 точки входа не меняются** → Task 5 сохраняет `fillImport(region, sum)`; scheduler-тест зелёный. ✔
- **§4.8 фронт /facilities + прогресс** → Task 6. ✔
- **§6 тесты** (классификатор/интеграция/репозиторий/миграция/фронт-билд/живой прогон) → Tasks 1,2,3,5,6,7. ✔
- **Type-consistency:** `findByMarketAndMonitorTendersTrue`/`findByMarketAndRegionAndMonitorTendersTrue`, `fetchTrdBuyPageByOrgBin`, `isMonitorTenders()`, `orgsTotal/orgsProcessed/currentOrgName`, `orgPage(...)`, `orgBinsQueried` — имена согласованы между задачами. ✔
- **Замечание:** конфиг `goszakup.import.keywords` в `application.yaml` больше не читается сервисом — оставлен как есть (безвреден), удаление — в бэклог. `KatoDictionary` + `fetchTrdBuyPageByKato` оставлены (не мешают, тесты зелёные).
