package com.vladoose.nir.integration.goszakup.dto;

/** Ссылка на файл «Техническая спецификация» лота на goszakup. ambiguous — имя лота в тендере не уникально. */
public record LotTechSpecRef(String filePath, String originalName, boolean ambiguous) {}
