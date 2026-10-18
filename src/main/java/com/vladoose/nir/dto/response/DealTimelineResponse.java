package com.vladoose.nir.dto.response;

import java.util.List;

/** Страница ленты по возрастанию времени; hasMore — есть события раньше первой строки. */
public record DealTimelineResponse(List<DealTimelineItem> items, boolean hasMore) {}
