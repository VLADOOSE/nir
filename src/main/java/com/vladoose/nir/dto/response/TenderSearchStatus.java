package com.vladoose.nir.dto.response;

import java.time.Instant;
import java.util.List;

/** Состояние поиска заявки: идёт ли, прогресс по словам, ожидание очереди, ошибка, результат. */
public record TenderSearchStatus(boolean running, int progress, int total, Instant startedAt, String waiting, String error,
                                 List<TenderSearchHit> hits) {}
