# Канал (площадка) тендера + кнопка «Открыть» по каналу — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** У каждого тендера кнопка «Открыть» ведёт на его площадку — goszakup.gov.kz или fms.ecc.kz (СК-Фармация) — по полю `Tender.platform`.

**Architecture:** Новое nullable-поле `Tender.platform` (enum GOSZAKUP/SK_PHARMACY). Frontend делает `portalLink/label/host` platform-aware (СК-Ф → fms.ecc.kz, та же URL-схема, что goszakup). Форма тендера получает селектор «Площадка». Без бэкфилла (null → фолбэк по рынку), без импорта СК-Ф.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JPA, MapStruct, Flyway, JUnit 5+AssertJ (`@SpringBootTest @Transactional`), Angular 21.

## Global Constraints

- `Tender.platform` — nullable enum `TenderPlatform{GOSZAKUP, SK_PHARMACY}`; **null → фолбэк по рынку** (RF→zakupki, KZ→goszakup) — обратная совместимость, RF не трогаем.
- URL-схема СК-Ф идентична goszakup: `https://fms.ecc.kz/ru/announce/index/{id}?tab=lots`; `{id}` = часть номера до дефиса (регекс `^(\d+)-\d+$`, как сейчас).
- Без бэкфилла существующих строк; без импорта СК-Ф; без канал-чипа; RF-логику не менять.
- ⚠️ Style-бюджет `tenders.component` 15.96/16 kB — селектор «Площадка» использует существующие стили `<label><select>`, нового CSS НЕ добавляем.
- Коммит: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Тесты — Bash `dangerouslyDisableSandbox: true`, перед прогоном `lsof -ti :8080 | xargs kill -9`.

---

### Task 1: Backend — `TenderPlatform` + `Tender.platform` + V11 + DTO/mapper + goszakup-writer

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/TenderPlatform.java`, `src/main/resources/db/migration/V11__tender_platform.sql`
- Modify: `entity/Tender.java`, `dto/response/TenderResponse.java`, `dto/request/TenderRequest.java`, `mapper/TenderMapper.java`, `integration/goszakup/GoszakupTenderWriter.java`
- Test: `src/test/java/com/vladoose/nir/mapper/TenderPlatformTest.java`

**Interfaces:**
- Produces: enum `TenderPlatform{GOSZAKUP, SK_PHARMACY}`; `Tender.getPlatform()/setPlatform(TenderPlatform)`; `TenderResponse.platform:String`; `TenderRequest.platform:String`; `TenderMapper.toPlatform(String)→TenderPlatform` (blank/invalid→null).

- [ ] **Step 1: Написать падающий тест**

Create `src/test/java/com/vladoose/nir/mapper/TenderPlatformTest.java`:

```java
package com.vladoose.nir.mapper;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderPlatformTest {

    @Autowired TenderMapper mapper;
    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void platform_persists_andMapsToResponseString() {
        MarketContext.set(Market.KZ);
        Facility f = facilityRepository.save(Facility.builder().name("P-" + UUID.randomUUID()).build());
        Tender t = tenderRepository.save(Tender.builder()
                .tenderNumber("363780-1").facility(f).status("ACTIVE")
                .platform(TenderPlatform.SK_PHARMACY).build());

        Tender reloaded = tenderRepository.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getPlatform()).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(mapper.toResponse(reloaded).getPlatform()).isEqualTo("SK_PHARMACY");
    }

    @Test
    void toPlatform_blankAndInvalid_null() {
        assertThat(mapper.toPlatform("SK_PHARMACY")).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(mapper.toPlatform("GOSZAKUP")).isEqualTo(TenderPlatform.GOSZAKUP);
        assertThat(mapper.toPlatform("")).isNull();
        assertThat(mapper.toPlatform("   ")).isNull();
        assertThat(mapper.toPlatform("BOGUS")).isNull();
        assertThat(mapper.toPlatform(null)).isNull();
    }
}
```

- [ ] **Step 2: Запустить — падает (нет TenderPlatform)**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.mapper.TenderPlatformTest"`
Expected: FAIL — `TenderPlatform`/`platform`/`toPlatform` не существуют.

- [ ] **Step 3: Создать enum `TenderPlatform`**

Create `src/main/java/com/vladoose/nir/entity/TenderPlatform.java`:

```java
package com.vladoose.nir.entity;

/** Площадка (канал) KZ-тендера. null → фолбэк по рынку (goszakup для KZ, zakupki для RF). */
public enum TenderPlatform {
    GOSZAKUP,     // goszakup.gov.kz
    SK_PHARMACY   // fms.ecc.kz (СК-Фармация, единый дистрибьютор)
}
```

- [ ] **Step 4: Добавить поле в `Tender`**

В `src/main/java/com/vladoose/nir/entity/Tender.java` после блока поля `source` (строки ~56-59) добавить:

```java
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TenderPlatform platform;
```
(`@Enumerated`/`@Column`/`EnumType` уже импортированы — используются `source`.)

- [ ] **Step 5: V11 миграция**

Create `src/main/resources/db/migration/V11__tender_platform.sql`:

```sql
-- Канал (площадка) тендера: GOSZAKUP / SK_PHARMACY. nullable → существующие KZ (null) фолбэк на goszakup.
ALTER TABLE tender ADD COLUMN platform VARCHAR(20);
```

- [ ] **Step 6: DTO — platform (String) в Response и Request**

В `src/main/java/com/vladoose/nir/dto/response/TenderResponse.java` рядом с `private String source;` добавить:
```java
    private String platform;
```
В `src/main/java/com/vladoose/nir/dto/request/TenderRequest.java` (рядом с прочими полями, напр. после `purchaseType`) добавить:
```java
    private String platform;
```

- [ ] **Step 7: `TenderMapper` — конверсия String↔platform**

В `src/main/java/com/vladoose/nir/mapper/TenderMapper.java` добавить импорт `import com.vladoose.nir.entity.TenderPlatform;` и внутри интерфейса — default-метод (MapStruct использует его для String→TenderPlatform в `toEntity`/`updateEntity`; `toResponse` маппит enum→String через `name()` автоматически, как `source`):

```java
    default TenderPlatform toPlatform(String v) {
        if (v == null || v.isBlank()) return null;
        try { return TenderPlatform.valueOf(v.trim()); } catch (IllegalArgumentException e) { return null; }
    }
```

- [ ] **Step 8: goszakup-writer ставит GOSZAKUP**

В `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupTenderWriter.java` добавить импорт `import com.vladoose.nir.entity.TenderPlatform;` и после строки `t.setSource(Source.PUBLIC_TENDER);` (~строка 56) добавить:

```java
        t.setPlatform(TenderPlatform.GOSZAKUP);
```

- [ ] **Step 9: Запустить тест — зелёный**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.mapper.TenderPlatformTest"`
Expected: PASS (2 теста).

- [ ] **Step 10: Компиляция + коммит**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (MapStruct пере-генерит `TenderMapperImpl` с platform).

```bash
git add src/main/java/com/vladoose/nir/entity/TenderPlatform.java \
        src/main/resources/db/migration/V11__tender_platform.sql \
        src/main/java/com/vladoose/nir/entity/Tender.java \
        src/main/java/com/vladoose/nir/dto/response/TenderResponse.java \
        src/main/java/com/vladoose/nir/dto/request/TenderRequest.java \
        src/main/java/com/vladoose/nir/mapper/TenderMapper.java \
        src/main/java/com/vladoose/nir/integration/goszakup/GoszakupTenderWriter.java \
        src/test/java/com/vladoose/nir/mapper/TenderPlatformTest.java
git commit -m "feat(platform): Tender.platform (GOSZAKUP/SK_PHARMACY) + V11 + маппинг + goszakup=GOSZAKUP

Nullable enum площадки тендера; MapStruct blank/invalid→null; goszakup-импорт ставит
GOSZAKUP. Без бэкфилла (null→фолбэк по рынку).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Frontend — ссылка/лейбл по каналу + селектор «Площадка»

**Files:**
- Modify: `frontend/src/app/services/market.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `TenderResponse.platform` (строка `GOSZAKUP`/`SK_PHARMACY`/null) из Task 1.
- Produces: (UI) кнопка «Открыть» + лейбл по каналу; селектор «Площадка» в форме.

- [ ] **Step 1: `market.service` — портал-методы platform-aware**

В `frontend/src/app/services/market.service.ts` заменить `portalLabel`/`portalHost`/`portalLink` на версии с необязательным `platform`:

```typescript
  portalLabel(platform?: string): string {
    if (platform === 'SK_PHARMACY') return 'СК-Фармация';
    if (platform === 'GOSZAKUP') return 'Госзакуп';
    return this.current === 'KZ' ? 'Госзакуп' : 'ЕИС';
  }
  portalHost(platform?: string): string {
    if (platform === 'SK_PHARMACY') return 'fms.ecc.kz';
    if (platform === 'GOSZAKUP') return 'goszakup.gov.kz';
    return this.current === 'KZ' ? 'goszakup.gov.kz' : 'zakupki.gov.ru';
  }

  /** Ссылка на тендер на площадке (по каналу тендера; null → фолбэк по рынку). */
  portalLink(tenderNumber: string, platform?: string): string {
    const q = encodeURIComponent(tenderNumber || '');
    const m = /^(\d+)-\d+$/.exec(tenderNumber || '');
    if (platform === 'SK_PHARMACY') {
      return m ? `https://fms.ecc.kz/ru/announce/index/${m[1]}?tab=lots`
               : `https://fms.ecc.kz/ru/searchanno`;
    }
    if (platform === 'GOSZAKUP') {
      return m ? `https://goszakup.gov.kz/ru/announce/index/${m[1]}`
               : `https://goszakup.gov.kz/ru/search/lots?filter[name]=${q}`;
    }
    if (this.current !== 'KZ') {
      return `https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=${q}`;
    }
    return m ? `https://goszakup.gov.kz/ru/announce/index/${m[1]}`
             : `https://goszakup.gov.kz/ru/search/lots?filter[name]=${q}`;
  }
```

- [ ] **Step 2: `tenders.component` — хелперы принимают тендер**

Заменить (строки ~1017-1019):
```typescript
  eisLink(tenderNumber: string): string { return this.market.portalLink(tenderNumber); }
  procurementPortalLabel(): string { return this.market.portalLabel(); }
  procurementPortalHost(): string { return this.market.portalHost(); }
```
на:
```typescript
  eisLink(t: any): string { return this.market.portalLink(t?.tenderNumber, t?.platform); }
  procurementPortalLabel(t?: any): string { return this.market.portalLabel(t?.platform); }
  procurementPortalHost(t?: any): string { return this.market.portalHost(t?.platform); }
```

- [ ] **Step 3: Шаблон — ссылки «Открыть» передают тендер**

Список (строка ~142-143), заменить:
```html
            <a *ngIf="!isDemoTender(t.tenderNumber)" class="eis-link" [href]="eisLink(t.tenderNumber)" target="_blank" rel="noopener" (click)="$event.stopPropagation()" [title]="'Открыть в ' + procurementPortalLabel() + ' ' + procurementPortalHost()">
              <svg lucideIcon="external-link" [size]="12"></svg> {{ procurementPortalLabel() }}
            </a>
```
на:
```html
            <a *ngIf="!isDemoTender(t.tenderNumber)" class="eis-link" [href]="eisLink(t)" target="_blank" rel="noopener" (click)="$event.stopPropagation()" [title]="'Открыть в ' + procurementPortalLabel(t) + ' ' + procurementPortalHost(t)">
              <svg lucideIcon="external-link" [size]="12"></svg> {{ procurementPortalLabel(t) }}
            </a>
```
Детальная (строка ~191-192), заменить:
```html
          <a *ngIf="!isDemoTender(selectedTender.tenderNumber)" class="eis-link-h2" [href]="eisLink(selectedTender.tenderNumber)" target="_blank" rel="noopener" [title]="'Открыть на ' + procurementPortalHost()">
            <svg lucideIcon="external-link" [size]="14"></svg> Открыть в {{ procurementPortalLabel() }}
          </a>
```
на:
```html
          <a *ngIf="!isDemoTender(selectedTender.tenderNumber)" class="eis-link-h2" [href]="eisLink(selectedTender)" target="_blank" rel="noopener" [title]="'Открыть на ' + procurementPortalHost(selectedTender)">
            <svg lucideIcon="external-link" [size]="14"></svg> Открыть в {{ procurementPortalLabel(selectedTender) }}
          </a>
```

- [ ] **Step 4: FormControl `platform` + reset/patch**

В `tenderForm` (строка ~904, после `contactEmail: new FormControl('')`) добавить (не забыть запятую на предыдущей строке):
```typescript
    ,platform: new FormControl('')
```
В `onAddTender()` (строка ~1392) заменить `this.tenderForm.reset({ status: 'DRAFT' });` на:
```typescript
    this.tenderForm.reset({ status: 'DRAFT', platform: '' });
```
В `onEditTender(t)` (patchValue, строки ~1396-1400) добавить в объект patch:
```typescript
      platform: t.platform || '',
```

- [ ] **Step 5: Селектор «Площадка» в форме (KZ)**

В блоке `.dims-row` со `status`/`purchaseType` (строки ~96-112), после `<label>Способ закупки …</label>` добавить (использует существующие стили label/select):
```html
          <label *ngIf="isKz()">Площадка
            <select formControlName="platform">
              <option value="">— по рынку (Госзакуп) —</option>
              <option value="GOSZAKUP">Госзакуп</option>
              <option value="SK_PHARMACY">СК-Фармация</option>
            </select>
          </label>
```

- [ ] **Step 6: `onSaveTender` — platform в body**

В `onSaveTender()` (строка ~1408, объект `body`) добавить поле:
```typescript
      platform: v.platform || null,
```

- [ ] **Step 7: Сборка фронта (в т.ч. style-бюджет)**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: `Application bundle generation complete`. ⚠️ НЕ должно быть НОВОГО `anyComponentStyle maximumError` по `tenders.component` (CSS не добавляли — селектор на существующих стилях).

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/services/market.service.ts \
        frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(platform): кнопка «Открыть» по каналу + селектор «Площадка» в форме

portalLink/label/host per-tender (СК-Ф→fms.ecc.kz/ru/announce/index/{id}); хелперы
принимают тендер; форма — селектор Площадка (KZ). null→фолбэк по рынку.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- `Tender.platform` enum + nullable + V11 → Task 1 Steps 3-5 ✓.
- DTO Request/Response + mapper (blank/invalid→null) → Steps 6-7 ✓.
- goszakup-импорт = GOSZAKUP → Step 8 ✓.
- portalLink/label/host per-tender (СК-Ф→fms.ecc.kz) → Task 2 Step 1-3 ✓.
- Селектор «Площадка» в форме + сохранение → Steps 4-6 ✓.
- Фолбэк по рынку (null), RF не трогаем → portalLink else-ветка ✓.
- Тесты: персист+response, blank/invalid→null → Task 1 Step 1 ✓.

**2. Placeholder scan:** нет TBD/«handle errors» — весь код приведён. ✓

**3. Type consistency:** `TenderPlatform{GOSZAKUP,SK_PHARMACY}`; `platform:String` в DTO; `toPlatform(String)→TenderPlatform`; фронт `portalLabel/Host(platform?:string)`, `portalLink(number, platform?)`, `eisLink(t)/procurementPortalLabel(t)/procurementPortalHost(t)`, форм-контрол `platform`. Согласовано Task 1↔Task 2. ✓

**Примечание:** `updateEntity` использует `NullValuePropertyMappingStrategy.IGNORE` → при апдейте без platform (null) существующее значение не затирается. goszakup-writer сеттит через `t.setPlatform` (не через маппер) — тоже ок.
