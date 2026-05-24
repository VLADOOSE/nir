# Дизайн: интеллектуальный подбор оборудования + замена иконок

**Дата:** 2026-05-25
**Проект:** АИС учёта участия в тендерах на медицинское оборудование (ООО «Регион-Мед»)
**Тип:** функциональная доработка (СППР-логика) + UI-стилизация

## 1. Контекст и проблема

Текущий «ключевой» механизм — эндпоинт `GET /api/equipment/match/{lotId}`,
который возвращает каталог оборудования, отфильтрованный по габаритам и весу,
**отсортированный по алфавиту**. Никакого ранжирования, оценок,
рекомендаций или объяснений. Менеджер видит «плоский» список и сам решает,
что выбрать.

Это делает систему обычным CRUD с фильтром, а не системой поддержки принятия
решений. Цель текущей итерации — превратить подбор оборудования в настоящий
СППР-модуль с composite-скорингом, объяснимостью и пользовательскими профилями
весов.

Параллельно меняем все эмодзи-«иконки» в UI на consistent-набор из библиотеки
векторных иконок (Lucide).

## 2. Цели

- Каждый кандидат на лот получает оценку 0–100 по 4 критериям с весами.
- Пользователь видит, **почему** именно этот кандидат рекомендуется
  (breakdown + текстовое объяснение).
- Пользователь может переключать профиль весов
  («Баланс» / «Максимум прибыли» / «Надёжность») или настраивать вручную.
- Топ-кандидат с оценкой ≥ 60 помечается как «Рекомендация СППР».
- Работает в условиях cold-start (нет истории): нейтральные оценки + баннер.
- Иконки во всём приложении заменены с эмодзи на Lucide.

## 3. Алгоритм скоринга (SAW — Simple Additive Weighting)

### 3.1 Общая формула

```
score = w₁·priceScore + w₂·marginScore + w₃·trackScore + w₄·dimScore
```

где `Σwᵢ = 1.0` (нормализуется автоматически из слайдеров 0–100;
если все слайдеры на 0 — равные веса).

### 3.2 Sub-scores

Все sub-scores ∈ [0, 100].

| Критерий | Формула | Источник данных | Cold-start |
|---|---|---|---|
| `priceScore` | `100 · (1 − avgOfferedCost / lot.maxCost)`, clamp [0, 100] | `AVG(apply_item.offered_cost)` по этому `med_equipment` (любой статус заявки) | нет данных → 50, `noData=true` |
| `marginScore` | `min(100, avgMarginPercent · 2)` (маржа 50%+ = максимум) | `AVG(apply_item.margin_percent)` по `equip_type` среди WON-заявок | нет данных → 50, `noData=true` |
| `trackScore` | `min(100, 25 · log₂(wins + 1))` (0 побед=0, 1=25, 3=50, 7=75, 15+=100) | `COUNT(DISTINCT activity_apply.id)` где `activity_apply.status='WON'` и этот `med_equipment` присутствует в любом из её `apply_item` | 0 побед = 0 (валидное значение, не «нет данных») |
| `dimScore` | `100 − 25 · avg(used_L, used_W, used_H, used_W_kg)`, где `used_x = equipment.x / lot.max_x` ∈ [0, 1] | поля `med_equipment` и `tender_lot` | если `lot.max_x` = null → этот габарит игнорируется в среднем |

**Обоснование `dimScore`:** условие «вписывается» уже соблюдается
SQL-фильтром на этапе шорт-листа, так что `dimScore` отражает запас:
меньше занятого габарита → выше балл (меньше рисков логистики/установки).

### 3.3 Поведение в условиях cold-start

`hasHistory` — флаг в ответе, истинный, если в БД есть хотя бы одна
WON-заявка глобально. Если ложный — UI выводит баннер: «Истории сделок пока
нет — рекомендации основаны только на габаритах». В этом случае
`priceScore` и `marginScore` всегда возвращают 50 для всех кандидатов,
а реальное ранжирование выезжает на `dimScore`.

## 4. API контракт

### 4.1 Новый эндпоинт

```
POST /api/equipment/match/{lotId}
Content-Type: application/json
```

**Request:**

```json
{
  "preset": "BALANCED",
  "weights": { "price": 25, "margin": 25, "track": 25, "dim": 25 }
}
```

- `preset` ∈ `BALANCED | MAX_PROFIT | RELIABILITY | CUSTOM`
- `weights` — обязательно только при `preset=CUSTOM`; иначе игнорируется
  и применяются константы пресета (см. 4.3).

**Дефолтные значения пресетов (4.3):**

| Preset | price | margin | track | dim |
|---|---|---|---|---|
| BALANCED | 25 | 25 | 25 | 25 |
| MAX_PROFIT | 35 | 40 | 15 | 10 |
| RELIABILITY | 15 | 15 | 50 | 20 |

### 4.2 Response

```json
{
  "lotId": 12,
  "lotMaxCost": 800000,
  "hasHistory": true,
  "weightsUsed": { "price": 0.25, "margin": 0.25, "track": 0.25, "dim": 0.25 },
  "preset": "BALANCED",
  "candidates": [
    {
      "equipmentId": 5,
      "name": "Дефибриллятор Mindray BeneHeart D3",
      "manufact": "Mindray",
      "equipType": "Дефибриллятор",
      "lengthMm": 220, "widthMm": 170, "heightMm": 80, "weightKg": 2.4,
      "spec": "...",
      "score": 77.0,
      "rank": 1,
      "recommended": true,
      "breakdown": {
        "price":  { "value": 75.0, "noData": false, "raw": "avg оф. 200 000 ₽ при потолке 800 000" },
        "margin": { "value": 88.0, "noData": false, "raw": "ср. маржа по типу: 44 %" },
        "track":  { "value": 50.0, "noData": false, "raw": "побед: 3" },
        "dim":    { "value": 95.0, "noData": false, "raw": "загрузка габаритов: 20 %" }
      },
      "bestDistributor": {
        "distributorId": 2,
        "name": "МедТех Поставка",
        "dealsCount": 2,
        "avgMarginPercent": 47.5
      },
      "estimatedPrice": 200000,
      "estimatedMargin": 600000
    }
  ]
}
```

Правило `recommended`: устанавливается `true` **только** у кандидата с
`rank=1` и **только** если `score ≥ 60`. Иначе у всех `recommended=false`.

### 4.3 Обратная совместимость

`GET /api/equipment/match/{lotId}` оставляем как алиас — внутри вызывает
POST с `preset=BALANCED`. Существующий фронт продолжит работать.

### 4.4 Ошибки

- `404` — `lotId` не найден.
- `400` — `preset=CUSTOM`, но `weights` не передан или `Σweights = 0`.

## 5. Backend архитектура

### 5.1 Новые/изменённые файлы

```
service/EquipmentScoringService.java        — оркестрация скоринга
service/EquipmentHistoryStatsService.java   — батч-сбор исторических агрегатов
dto/request/MatchRequest.java               — preset + weights
dto/response/EquipmentMatchResponse.java    — ответ с inner-классами
controller/MedEquipmentController.java      — POST /match/{lotId} + GET-алиас
repository/ApplyItemRepository.java         — 3 новых JPQL/native-метода
```

### 5.2 Поток выполнения

1. Контроллер принимает `lotId` + `MatchRequest`.
2. Преобразует preset → нормализованные веса (`Σ=1.0`); при `CUSTOM`
   нормализует переданные слайдеры.
3. Передаёт в `EquipmentScoringService.scoreLot(lotId, weights)`.
4. Сервис:
   - грузит `TenderLot` (404 если нет);
   - вызывает `MedEquipmentRepository.findMatchingEquipment(...)` для
     получения «жёсткого» шорт-листа кандидатов (фильтр по габаритам/типу);
   - вызывает `EquipmentHistoryStatsService.collect(equipmentIds, equipTypes)` —
     один батч-проход, без N+1;
   - для каждого кандидата считает 4 sub-score + собирает `Candidate`-DTO
     с `breakdown.raw` (человекочитаемое объяснение);
   - сортирует `score DESC`, проставляет `rank`;
   - top-1 со `score ≥ 60` получает `recommended=true`;
   - проверяет `hasHistory` глобальным `EXISTS`-запросом.
5. Возвращает `EquipmentMatchResponse`.

### 5.3 Запросы для исторических агрегатов

3 запроса батчем (один вызов JDBC на каждый):

**1. Per-equipment agg** (avg offered cost + win count):
```sql
SELECT ai.med_equip_id,
       AVG(ai.offered_cost) AS avg_cost,
       COUNT(DISTINCT ai.apply_id) FILTER (WHERE aa.status='WON') AS wins
FROM apply_item ai
JOIN activity_apply aa ON ai.apply_id = aa.id
WHERE ai.med_equip_id IN (:ids)
GROUP BY ai.med_equip_id
```

`wins` через `COUNT(DISTINCT apply_id)` — чтобы оборудование, попавшее в
несколько строк одной выигранной заявки, считалось одним выигрышем.

**2. Per-type margin agg:**
```sql
SELECT me.equip_type, AVG(ai.margin_percent) AS avg_margin
FROM apply_item ai
JOIN med_equipment me ON ai.med_equip_id = me.id
JOIN activity_apply aa ON ai.apply_id = aa.id
WHERE aa.status='WON' AND me.equip_type IN (:types)
GROUP BY me.equip_type
```

**3. Best distributor per equipment:**
```sql
SELECT ai.med_equip_id, ai.distributor_id, d.name,
       COUNT(*) AS deals, AVG(ai.margin_percent) AS avg_margin
FROM apply_item ai
JOIN distributor d ON ai.distributor_id = d.id
JOIN activity_apply aa ON ai.apply_id = aa.id
WHERE aa.status='WON' AND ai.med_equip_id IN (:ids)
GROUP BY ai.med_equip_id, ai.distributor_id, d.name
ORDER BY ai.med_equip_id, AVG(ai.margin_percent) DESC
```

В Java берём первую запись на каждый `med_equip_id` (best distributor).

Кэширования нет — выборки маленькие (десятки оборудования, десятки заявок).

## 6. Frontend UX

### 6.1 Размещение

Заменяем существующую секцию подбора в `tender-detail.component.ts`
(открывается по кнопке «Подобрать» на строке лота).

### 6.2 Структура экрана

```
┌─────────────────────────────────────────────────────────────────┐
│ Подбор оборудования для лота №3                       [✕ Закрыть]│
├─────────────────────────────────────────────────────────────────┤
│ Профиль: [Баланс ●] [Макс. прибыль] [Надёжность] [Свой]         │
│                                                                  │
│ ▾ Свой профиль (раскрывается, если выбран):                     │
│   Цена   [─────●─────] 25                                       │
│   Маржа  [───●───────] 20                                       │
│   Опыт   [─────────●─] 40                                       │
│   Габар. [──●───────] 15                                       │
│   Σ = 100  (нормализуется к 1.0 на бэке)                        │
├─────────────────────────────────────────────────────────────────┤
│ ⭐ Рекомендация СППР — Дефибриллятор Mindray D3 (82 балла)       │
│    Лучший дистрибьютор: МедТех Поставка (ср. маржа 47.5%)       │
├─────────────────────────────────────────────────────────────────┤
│ # │ Наименование          │Score│ Цена │Маржа│Опыт │Габар│ ▾   │
│ 1 │ Дефибриллятор Mindray │ 82  │ ▇▇▇  │ ▇▇▇▇│ ▇▇  │ ▇▇▇▇│ [+] │
│ 2 │ Дефибриллятор Philips │ 71  │ ▇▇   │ ▇▇▇ │ ▇▇▇ │ ▇▇▇▇│ [+] │
│ 3 │ Дефибриллятор Schiller│ 58  │ ▇▇▇▇ │ ▇▇  │ ▇   │ ▇▇▇ │ [+] │
└─────────────────────────────────────────────────────────────────┘
```

### 6.3 Раскрытие строки

Клик `[+]` показывает:
- 4 строки `breakdown.*.raw` (например, «ср. маржа по типу: 44%»;
  «побед: 3»; «загрузка габаритов: 20%»);
- Кнопку «Запросить КП у {bestDistributor.name}» с предзаполненным
  дистрибьютором (использует существующий workflow КП без изменений).

### 6.4 Cold-start баннер

Когда `hasHistory: false`:
```
⚠ Истории сделок пока нет — рекомендации основаны только на габаритах.
  После первых выигранных тендеров система будет учитывать маржу,
  цену и опыт.
```

### 6.5 Реактивность

- Смена пресета → немедленный POST.
- Движение слайдера → debounce 300 мс → POST.
- `preset` + `weights` (для CUSTOM) сохраняются в `localStorage`
  (`smartMatch.preset`, `smartMatch.weights`).

### 6.6 Out of scope (YAGNI)

- Экспорт результатов подбора в Excel/PDF.
- История запусков подбора.
- Сравнение нескольких лотов одновременно.

## 7. Замена иконок

### 7.1 Библиотека

**Lucide Angular** (`lucide-angular`). Обоснование:
- Официальный Angular-пакет, tree-shakable.
- Тонкие монохромные иконки — попадают в строгий деловой стиль.
- Покрытие: все нужные понятия (Stethoscope, Activity, BarChart3,
  Building2, Truck, Mail, Calendar, FileText и т.д.).
- Альтернативы (Heroicons — нет официального Angular-пакета;
  Tabler — менее строгий) отклонены.

### 7.2 Скоуп замены

- Все эмодзи в файлах `layout`, `dashboard`, `applies`, `reports`.
- Sidebar навигация: добавить иконки рядом с текстом.
- Кнопки таблиц: edit/delete/add.
- Header: поиск, профиль/выход.

### 7.3 Маппинг (выборка)

| Сейчас | Lucide |
|---|---|
| `📊` (Excel) | `FileSpreadsheet` |
| `📧` (Mail) | `Mail` |
| `💰` (Profit) | `TrendingUp` |
| `📋` (Applies) | `ClipboardList` |
| `🏥` (Facilities) | `Building2` |
| `🚚` (Distributors) | `Truck` |
| Tenders nav | `FileText` |
| Equipment nav | `Stethoscope` |
| Reports nav | `BarChart3` |
| Dashboard nav | `LayoutDashboard` |
| Search | `Search` |
| Add | `Plus` |
| Edit | `Pencil` |
| Delete | `Trash2` |
| Filter | `Filter` |

### 7.4 Подключение

```ts
// app.config.ts
import { LucideAngularModule, Building2, Truck, ... } from 'lucide-angular';

providers: [
  importProvidersFrom(
    LucideAngularModule.pick({ Building2, Truck, FileText, ... })
  )
]
```

В шаблонах: `<lucide-icon name="building-2" [size]="16"></lucide-icon>`.

Только выбранные иконки попадают в бандл — размер не раздувается.

## 8. Тестирование

### 8.1 Бэкенд

- Unit-тест `EquipmentScoringService` на 5 сценариев:
  1. Полный набор истории → корректный composite + порядок.
  2. Cold start (нет WON) → `hasHistory=false`, все `priceScore`/`marginScore`=50.
  3. Один кандидат — `rank=1`, `recommended` зависит от score ≥ 60.
  4. Нормализация весов: `[50,50,0,0]` → `[0.5, 0.5, 0, 0]`.
  5. `preset=CUSTOM` без weights → 400.
- Integration-тест: H2 + seed data → проверка end-to-end через MockMvc.

### 8.2 Фронтенд

- Ручная проверка: открыть страницу тендера, нажать «Подобрать», переключить
  пресеты, подвигать слайдеры → таблица перестраивается.
- Cold-start: очистить таблицы `apply_item` / `activity_apply` → убедиться,
  что баннер появляется и ранжирование валидно.

## 9. План миграции данных

Изменений в схеме БД нет. Все необходимые поля
(`apply_item.offered_cost`, `margin`, `margin_percent`) уже существуют.

## 10. Открытые вопросы

Нет.
