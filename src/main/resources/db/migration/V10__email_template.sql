-- V10: шаблон письма КП, редактируемый из UI, по одной строке на рынок.
-- Дефолтный текст живёт в коде (KpEmailComposer.DEFAULT_SUBJECT/DEFAULT_BODY) — единственный
-- источник; при отсутствии строки композер берёт дефолт. Поэтому таблицу НЕ сеем (нет дублирования
-- текста в SQL и дрейфа). Строка появляется, когда оператор сохранит шаблон.
CREATE TABLE email_template (
  id               BIGSERIAL PRIMARY KEY,
  market           VARCHAR(2) NOT NULL UNIQUE,
  subject_template TEXT NOT NULL,
  body_template    TEXT NOT NULL,
  updated_at       TIMESTAMPTZ
);
