-- Калитка по коду устройства для ais.westmed.kz (спека docs/superpowers/specs/2026-09-28-device-gate-design.md §4).
-- Общая таблица, без рынка: доступ к системе не зависит от переключателя РФ/KZ.
CREATE TABLE trusted_device (
    id              BIGSERIAL PRIMARY KEY,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 (hex) ключа устройства; сам ключ не хранится
    status          VARCHAR(10)  NOT NULL,          -- PENDING / TRUSTED / REJECTED / REVOKED / EXPIRED
    code            VARCHAR(6)   NOT NULL,          -- код запроса без дефиса (показ — «7K4-QM2»)
    requester_name  VARCHAR(60)  NOT NULL,          -- что ввели на калитке; НЕ доверенное
    label           VARCHAR(100),                   -- подпись админа при допуске
    user_agent      VARCHAR(300),
    ip              VARCHAR(45),
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    decided_by      VARCHAR(100),                   -- логин админа, принявшего последнее решение
    last_seen_at    TIMESTAMPTZ
);
CREATE INDEX idx_trusted_device_status ON trusted_device (status);
-- код уникален среди ожидающих: по нему админ находит нужный запрос
CREATE UNIQUE INDEX uq_trusted_device_pending_code ON trusted_device (code) WHERE status = 'PENDING';
