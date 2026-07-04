# Честная score-семантика в панели «Реестр» — план имплементации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Панель «Реестр» перестаёт показывать врущие «100%» для одно-словных лотов — при бедном сигнале метка «✓ по названию» + подсказка разобрать ТЗ; проценты только когда есть чем различать.

**Architecture:** `RegistryMatchService` — общий `computeLotMatch` → `LotMatch{candidates, distinctive}` (distinctive = ≥2 токена или бренд-путь); `candidatesForLot(List)` для LotSourcing неизменен; новый `matchForLotUi` → DTO `{candidates, distinctive, techSpecParsed}`; эндпоинт возвращает DTO. Фронт: метка вместо % при `!distinctive`, баннер при `!distinctive && !techSpecParsed`.

**Tech Stack:** Java 17 / Spring Boot 3.5.6, Angular 21. Без миграций/зависимостей.

**Spec:** `docs/superpowers/specs/2026-07-05-registry-honest-score-design.md`.

## Global Constraints

- Ветка: `feat/registry-honest-score` (активна). Sandbox off для `./gradlew`; перед полным прогоном `lsof -ti :8080 | xargs kill -9 || true`.
- Гейт: `./gradlew test` — только 2 известных `ApplyAutoFillServiceTest`; `cd frontend && npm run build`.
- `candidatesForLot(Long,int): List<RegistryCandidateResponse>` — контракт для `LotSourcingService` НЕ менять.
- Миграций нет; `@FilterDef`/market не трогать. Тестовые данные — префикс `ZZ`; `MarketContext.set` + `@AfterEach clear()`.
- Trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Субагенты — Fable 5.

---

### Task 1: Бэкенд — `computeLotMatch` + `matchForLotUi` + DTO + эндпоинт

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/LotRegistryMatchResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/RegistryMatchService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/TenderLotController.java`
- Test: `src/test/java/com/vladoose/nir/service/RegistryMatchUiTest.java`

**Interfaces:**
- Consumes: `LotQueryTokenizer.tokenize`, `TechSpecExtractor.characteristics`, `findCandidates`, `searchByTokens`, `toCandidate` (все существуют).
- Produces:
  - `record LotMatch(List<RegistryCandidateResponse> candidates, boolean distinctive)` (приватный вложенный);
  - `RegistryMatchService.matchForLotUi(Long lotId, int limit): LotRegistryMatchResponse`;
  - `LotRegistryMatchResponse {List<RegistryCandidateResponse> candidates; boolean distinctive; boolean techSpecParsed}` (Lombok @Data);
  - `candidatesForLot(Long,int): List<RegistryCandidateResponse>` — прежняя сигнатура, теперь делегат;
  - `GET /api/lots/{id}/registry-candidates` → `LotRegistryMatchResponse`.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/service/RegistryMatchUiTest.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RegistryMatchUiTest {

    @Autowired RegistryMatchService service;
    @Autowired TenderRepository tenderRepository;

    @BeforeEach
    void setUp() { MarketContext.set(Market.KZ); }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Long lot(String equipName, String manufact, String requiredSpec) {
        Tender t = new Tender();
        t.setTenderNumber("ZZ-UISC-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(equipName);
        l.setManufact(manufact);
        l.setRequiredSpec(requiredSpec);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0).getId();
    }

    @Test
    void oneWordLot_notDistinctive_noTechSpec() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Центрифуга", null, null), 5);
        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.isDistinctive()).isFalse();       // 1 токен → % врёт
        assertThat(r.isTechSpecParsed()).isFalse();
    }

    @Test
    void multiWordLot_distinctive() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Дефибриллятор монитор бифазный", null, null), 5);
        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.isDistinctive()).isTrue();          // ≥2 токена
    }

    @Test
    void parsedTechSpec_distinctiveAndFlagged() {
        Long id = lot("Центрифуга", null, """
                Приложение 2
                характеристики
                закупаемых товаров:
                Максимальная скорость центрифугирования 4500 об/мин, вместимость 8 пробирок
                """);
        LotRegistryMatchResponse r = service.matchForLotUi(id, 5);
        assertThat(r.isTechSpecParsed()).isTrue();        // characteristics != null
        assertThat(r.isDistinctive()).isTrue();           // имя(1) + токены ТЗ ≥2
    }

    @Test
    void brandSet_distinctive() {
        LotRegistryMatchResponse r = service.matchForLotUi(lot("Монитор", "Mindray", null), 5);
        assertThat(r.isDistinctive()).isTrue();           // бренд-путь
    }
}
```

- [ ] **Step 2: Прогнать — падает (компиляция).**

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryMatchUiTest"`
Expected: FAIL (нет `matchForLotUi`/DTO).

- [ ] **Step 3: DTO**

`src/main/java/com/vladoose/nir/dto/response/LotRegistryMatchResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

/** Ответ панели «Реестр» у лота: кандидаты + метаданные достоверности матча. */
@Data
public class LotRegistryMatchResponse {
    private List<RegistryCandidateResponse> candidates;
    private boolean distinctive;   // есть чем различать записи (≥2 значимых токена или задан бренд); иначе % врёт
    private boolean techSpecParsed; // ТЗ разобрано (в requiredSpec есть блок характеристик)
}
```

- [ ] **Step 4: Рефактор сервиса**

В `RegistryMatchService.java`: импорт `com.vladoose.nir.dto.response.LotRegistryMatchResponse`.
Заменить метод `candidatesForLot` (строки 80–98) на общий `computeLotMatch` + тонкие обёртки:

```java
    /** Общий матч по лоту: кандидаты + флаг «есть чем различать» (distinctive). */
    private record LotMatch(List<RegistryCandidateResponse> candidates, boolean distinctive) {}

    private LotMatch computeLotMatch(TenderLot lot, int limit) {
        if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
            return new LotMatch(findCandidates(lot.getEquipName(), lot.getManufact(), limit), true);
        }
        List<WeightedToken> tokens = LotQueryTokenizer.tokenize(
                lot.getEquipName(), TechSpecExtractor.characteristics(lot.getRequiredSpec()));
        if (tokens.isEmpty()) {
            return new LotMatch(findCandidates(lot.getEquipName(), lot.getManufact(), limit), false);
        }
        String toks = tokens.stream().map(WeightedToken::token).collect(Collectors.joining("|"));
        String wgts = tokens.stream()
                .map(t -> String.format(Locale.ROOT, "%.2f", t.weight()))
                .collect(Collectors.joining("|"));
        List<RegistryCandidateResponse> candidates = registryRepository.searchByTokens(toks, wgts, limit).stream()
                .map(this::toCandidate)
                .toList();
        // ≥2 значимых токена → есть чем различать записи; 1 токен → совпадение только по названию
        return new LotMatch(candidates, tokens.size() >= 2);
    }

    /** Кандидаты реестра по лоту (для LotSourcingService) — прежний контракт. */
    public List<RegistryCandidateResponse> candidatesForLot(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        return computeLotMatch(lot, limit).candidates();
    }

    /** Для панели «Реестр»: кандидаты + метаданные достоверности матча. */
    public LotRegistryMatchResponse matchForLotUi(Long lotId, int limit) {
        TenderLot lot = tenderLotRepository.findById(lotId)
                .orElseThrow(() -> new NotFoundException("Лот не найден: id=" + lotId));
        LotMatch m = computeLotMatch(lot, limit);
        LotRegistryMatchResponse r = new LotRegistryMatchResponse();
        r.setCandidates(m.candidates());
        r.setDistinctive(m.distinctive());
        r.setTechSpecParsed(TechSpecExtractor.characteristics(lot.getRequiredSpec()) != null);
        return r;
    }
```

(старый javadoc над `candidatesForLot` «бренд задан → …» удалить — логика уехала в `computeLotMatch`.)

- [ ] **Step 5: Эндпоинт**

В `TenderLotController.java` заменить метод `registryCandidates` (строки 105–110):

```java
    /** Кандидаты реестра НЦЭЛС по лоту + достоверность матча (для честного показа «похожести»). */
    @GetMapping("/{id}/registry-candidates")
    public com.vladoose.nir.dto.response.LotRegistryMatchResponse registryCandidates(
            @PathVariable Long id, @RequestParam(defaultValue = "5") int limit) {
        return registryMatchService.matchForLotUi(id, Math.min(limit, 20));
    }
```

- [ ] **Step 6: Прогнать — зелёный** (+ регресс LotSourcing/реестр)

Run (sandbox off): `./gradlew test --tests "com.vladoose.nir.service.RegistryMatchUiTest" --tests "com.vladoose.nir.service.RegistryLotMatchTest" --tests "com.vladoose.nir.service.LotSourcingServiceTest"`
Expected: PASS (UI 4/4 + реестр-golden + sourcing не сломаны).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/LotRegistryMatchResponse.java \
  src/main/java/com/vladoose/nir/service/RegistryMatchService.java \
  src/main/java/com/vladoose/nir/controller/TenderLotController.java \
  src/test/java/com/vladoose/nir/service/RegistryMatchUiTest.java
git commit -m "feat(registry): matchForLotUi — distinctive/techSpecParsed для честного показа похожести

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Фронт — метка «по названию» + баннер + пояснение

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `GET /api/lots/{id}/registry-candidates` → `{candidates, distinctive, techSpecParsed}` (T1).
- Produces: registry-panel с меткой/баннером; `isImportedTender()` (существует).

- [ ] **Step 1: ApiService — тип ответа объект**

В `api.service.ts` метод `getLotRegistryCandidates` (строка ~89): вернуть `Observable<any>` (объект), не массив:

```ts
  getLotRegistryCandidates(lotId: number, limit = 5): Observable<any> {
    return this.http.get<any>(`${this.base}/lots/${lotId}/registry-candidates`, { params: { limit } });
  }
```

- [ ] **Step 2: `onLotRegistry` — распаковка обёртки**

В `tenders.component.ts` заменить `onLotRegistry` (строки ~962–974):

```ts
  onLotRegistry(l: any) {
    this.registryPanel = { lot: l, loading: true, items: [], distinctive: true, techSpecParsed: true };
    this.cdr.detectChanges();
    this.api.getLotRegistryCandidates(l.id, 5).subscribe({
      next: (r: any) => {
        this.registryPanel = {
          lot: l, loading: false,
          items: r?.candidates || [],
          distinctive: !!r?.distinctive,
          techSpecParsed: !!r?.techSpecParsed,
        };
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.registryPanel = null;
        this.notify.error('Ошибка поиска в реестре: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }
```

(если фактические имена в текущем `onLotRegistry` иные — сохранить существующую error-ветку, поменять только next на распаковку `r.candidates/distinctive/techSpecParsed`.)

- [ ] **Step 3: Шапка панели + баннер + метка**

Заменить блок registry-panel (строки ~304–324) шапку/легенду/баннер/ячейку «Похожесть»:

Шапка — добавить постоянное пояснение и заголовок колонки «Соответствие»:

```html
        <div class="registry-panel-head">
          <span><b>Реестр НЦЭЛС РК:</b> {{ registryPanel.lot.equipName }}</span>
          <button class="btn btn-cancel" (click)="closeRegistryPanel()">✕ Закрыть</button>
        </div>
        <div class="registry-note">Реестр НЦЭЛС — допуск (№ РУ); габариты/вес здесь не хранятся, соответствие — по совпадению наименования.</div>
        <div *ngIf="!registryPanel.loading && !registryPanel.distinctive && !registryPanel.techSpecParsed && isImportedTender()" class="registry-hint">
          ⚠ Совпадение только по названию — модели в реестре неразличимы. Нажмите «ТЗ», чтобы разбор техспецификации уточнил подбор.
        </div>
```

Заголовок колонки: `<th>Похожесть</th>` → `<th>Соответствие</th>`.
Ячейка соответствия: `<td><span class="score-badge" [class.score-good]="c.score >= 0.35">{{ scorePct(c) }}%</span></td>` →

```html
              <td>
                <span *ngIf="registryPanel.distinctive" class="score-badge" [class.score-good]="c.score >= 0.35">{{ scorePct(c) }}%</span>
                <span *ngIf="!registryPanel.distinctive" class="score-badge score-name" title="Совпало наименование; для различения моделей разберите ТЗ">✓ по названию</span>
              </td>
```

Стили (рядом с `.score-badge.score-good`, строка ~534):

```css
    .score-badge.score-name { background: #eef2ff; color: #3730a3; }
    .registry-note { font-size: 12px; color: #6b7280; margin: 4px 0 8px; }
    .registry-hint { background: #fef3c7; border-left: 3px solid #f59e0b; padding: 8px 12px; border-radius: 4px; margin-bottom: 8px; font-size: 13px; color: #92400e; }
```

- [ ] **Step 4: Сборка фронта**

Run: `cd frontend && npm run build` → успех.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "feat(ui): реестр — метка «по названию» вместо врущих % при бедном сигнале + подсказка разобрать ТЗ

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Гейт + live + CLAUDE.md + мерж

- [ ] **Step 1:** `lsof -ti :8080 | xargs kill -9 || true && ./gradlew test` → только 2 известных.
- [ ] **Step 2:** `cd frontend && npm run build` → успех.
- [ ] **Step 3:** live (Playwright, бэк с токеном, KZ):
  1. Найти одно-словный импортный лот без разобранного ТЗ (напр. свежий из ленты «Центрифуга»/«Дистиллятор» — проверить в БД `required_spec IS NULL`) → «Реестр»: метки **«✓ по названию»** + баннер «разберите ТЗ» + легенда в шапке.
  2. На нём «ТЗ» → «Реестр» снова: **проценты**, баннер исчез.
  3. Рентген-лот 852 (ТЗ разобрано) → проценты как раньше (регресс).
  Скриншоты.
- [ ] **Step 4:** CLAUDE.md §8 — к пункту «Умный реестр-матч» добавить: «Панель `matchForLotUi` отдаёт `distinctive` (≥2 токена/бренд) + `techSpecParsed`; UI при `!distinctive` показывает «✓ по названию» вместо врущих 100% (одно-словный лот: `word_similarity` одного слова = 1.0 всем) + подсказку разобрать ТЗ. `candidatesForLot(List)` для `LotSourcingService` неизменен.»
- [ ] **Step 5:** Commit docs → whole-branch review (Fable) → фиксы → `git checkout main && git merge --ff-only feat/registry-honest-score && git branch -d …` → тур.

## Порядок

T1 → T2 → T3 (линейно).
