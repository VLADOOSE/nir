package com.vladoose.nir.dto.request;

import lombok.Data;

/** Заметка к модели в работе — «почему эта модель» (спека 2026-10-20-lot-model-in-work §5.5). */
@Data
public class LotModelNoteRequest {
    private String note;
}
