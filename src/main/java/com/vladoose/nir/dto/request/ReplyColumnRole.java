package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LineField;

/** Роль колонки таблицы файла ответа, выбранная оператором на экране «Ответ поставщика». */
public record ReplyColumnRole(int index, LineField role) {}
