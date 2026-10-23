-- Переразбор протоколов «из одного источника» (2026-10-23): разборщик брал только первый лот многолотового протокола
-- (остальным — «лота нет в протоколе»), «Цену поставщика» (она за единицу) считал суммой и делил на количество лота, в PDF терял
-- перенесённое название поставщика. Записанные итоги таких лотов с файлом протокола помечаются: проход итогов один раз скачает
-- файл и разберёт его исправленным разборщиком (в том числе у окончательных итогов — их обычный проход не трогает).
ALTER TABLE tender_lot_result ADD COLUMN protocol_reparse BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE tender_lot_result r SET protocol_reparse = TRUE
FROM tender_lot l
WHERE l.id = r.tender_lot_id
  AND r.protocol_url IS NOT NULL
  AND r.protocol_status IN ('PARSED', 'UNKNOWN_FORMAT')
  AND btrim(l.source_lot_code) ~ '-ОИ[0-9]*$';
