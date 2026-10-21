package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Позиция письма из бота: лот, позиция каталога модели в работе на момент черновика, количество (пусто — уточняется). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotLetterItem(long lotId, Long equipmentId, Integer qty) {}
