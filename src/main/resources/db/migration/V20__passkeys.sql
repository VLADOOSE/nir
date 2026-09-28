-- Вход по Face ID / Touch ID — passkeys (спека docs/superpowers/specs/2026-09-28-passkeys-login-design.md §4).
-- Хранилище ключей — схема Spring Security 6.5.5 без изменений колонок (user-entities-schema.sql и
-- user-credentials-schema-postgres.sql из spring-security-web): её читают и пишут
-- JdbcPublicKeyCredentialUserEntityRepository и JdbcUserCredentialRepository. Таблицы общие, без рынка.
CREATE TABLE user_entities (
    id           VARCHAR(1000) NOT NULL,   -- случайный id пользователя WebAuthn (base64url), не user_account.id
    name         VARCHAR(100)  NOT NULL,   -- логин = user_account.username
    display_name VARCHAR(200),
    PRIMARY KEY (id)
);

CREATE TABLE user_credentials (
    credential_id                VARCHAR(1000) NOT NULL,   -- id ключа (base64url)
    user_entity_user_id          VARCHAR(1000) NOT NULL,
    public_key                   BYTEA         NOT NULL,   -- ОТКРЫТЫЙ ключ; закрытый не покидает устройство
    signature_count              BIGINT,
    uv_initialized               BOOLEAN,
    backup_eligible              BOOLEAN       NOT NULL,
    authenticator_transports     VARCHAR(1000),
    public_key_credential_type   VARCHAR(100),
    backup_state                 BOOLEAN       NOT NULL,
    attestation_object           BYTEA,
    attestation_client_data_json BYTEA,
    created                      TIMESTAMP,
    last_used                    TIMESTAMP,
    label                        VARCHAR(1000) NOT NULL,   -- подпись («iPhone · Safari»)
    PRIMARY KEY (credential_id)
);

-- Наше дополнение: ключи намертво привязаны к учётке. Spring ищет владельца ключа по ЛОГИНУ, а логин
-- в «Пользователях» редактируется: переименование переносит ключи, удаление учётки удаляет их, и новая
-- учётка со старым логином чужих ключей не получает.
ALTER TABLE user_entities ADD CONSTRAINT uq_user_entities_name UNIQUE (name);
ALTER TABLE user_entities ADD CONSTRAINT fk_user_entities_account
    FOREIGN KEY (name) REFERENCES user_account (username) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE user_credentials ADD CONSTRAINT fk_user_credentials_entity
    FOREIGN KEY (user_entity_user_id) REFERENCES user_entities (id) ON DELETE CASCADE;
CREATE INDEX idx_user_credentials_entity ON user_credentials (user_entity_user_id);
