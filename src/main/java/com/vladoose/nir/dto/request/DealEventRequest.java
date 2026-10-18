package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.DealEventType;

/** Заметка или звонок по сделке. */
public record DealEventRequest(DealEventType type, String text) {}
