package com.vladoose.nir.dto.response;

/** Проекция частотности токена: сколько записей реестра пословно похожи на токен (для IDF-веса). */
public interface TokenDfRow {
    String getTok();
    long getDf();
}
