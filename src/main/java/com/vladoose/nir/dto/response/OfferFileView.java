package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ReplyTable;

import java.util.List;

/** Документ готового КП для просмотра (решение при планировании 2): вид, страницы PDF, таблицы файла. */
public record OfferFileView(String kind, Integer pages, List<ReplyTable> tables) {}
