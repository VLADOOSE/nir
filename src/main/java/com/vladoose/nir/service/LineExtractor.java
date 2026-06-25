package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.ImportPreviewResponse;
import com.vladoose.nir.entity.LineField;

import java.util.Map;

public interface LineExtractor {
    /** content — байты Excel; learned — карта нормализованный_заголовок → поле (словарь + выученное). */
    ImportPreviewResponse extract(byte[] content, String filename, Map<String, LineField> learned);
}
