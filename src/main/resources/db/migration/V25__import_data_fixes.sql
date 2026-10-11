-- Импорт тендеров, пакет 3 «Данные» (docs/reviews/2026-10-06-import-review.md, B1/B2/B4/B5).

-- B1: goszakup клал в «Макс. цену» лота (max_cost, цена за единицу) СУММУ лота (amount) — «300 шт · до 54 000 ₸»
-- при 180 ₸ за штуку. Код теперь пишет amount / count; здесь — уже записанное.
-- Делим только лоты тендеров, где сумма max_cost лотов равна сумме тендера: это доказывает, что в max_cost лежат
-- суммы лотов (на проде 2026-10-11 — 142 из 142 тендеров goszakup), и защищает от повторного деления.
-- platform IS NULL — импорт goszakup до появления колонки площадки (V11, только в базах разработки).
-- 0,00 после округления → пусто: CHECK max_cost > 0.
WITH lot_sums AS (
    SELECT t.id
    FROM tender t
    JOIN tender_lot l ON l.tender_id = t.id
    WHERE t.source_ext_id IS NOT NULL
      AND (t.platform = 'GOSZAKUP' OR (t.platform IS NULL AND t.market = 'KZ'))
    GROUP BY t.id, t.total_cost
    HAVING sum(l.max_cost) = t.total_cost
)
UPDATE tender_lot l
SET max_cost = NULLIF(round(l.max_cost / l.quantity, 2), 0)
WHERE l.tender_id IN (SELECT id FROM lot_sums)
  AND l.max_cost IS NOT NULL
  AND l.quantity > 0;

-- B2: время окончания приёма заявок. Обе площадки его отдают («2026-10-09 10:25:00»), а хранилась только дата —
-- оператор не видел, что приём закончится в 10:25, а тендер закрывался по дате.
-- Местное время площадки (Казахстан, UTC+5), без пояса: показывается как есть, сравнивается с «сейчас» по часам рынка.
-- deadline (дата) остаётся: на ней фильтры, дашборд, отчёты и ручные тендеры (у них времени нет).
ALTER TABLE tender ADD COLUMN deadline_at TIMESTAMP;

-- B4: лот без количества (СК-Фармация, «приказ ЕД») уходил поставщику как «1 шт.»: пустое количество
-- база не принимала, и фронт подставлял 1. Пусто = «кол-во уточняется»; CHECK > 0 остаётся.
ALTER TABLE price_request_item ALTER COLUMN requested_quantity DROP NOT NULL;

-- B5: лот, снятый с площадки, на который уже есть запрос КП или позиция заявки, удалить нельзя (внешние ключи
-- price_request_item / apply_item) — раньше это откатывало обновление всего тендера на каждом прогоне.
-- Такой лот остаётся с отметкой «снят с площадки»; вернулся на площадку — отметка снимается.
ALTER TABLE tender_lot ADD COLUMN source_removed_at TIMESTAMPTZ;
