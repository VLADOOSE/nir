package com.vladoose.nir.dto.request;

import java.util.List;

/** «📋 Для ИИ» по списку: id тендеров в порядке экрана (ближайший срок — первым). */
public record AiBriefRequest(List<Long> ids) {}
