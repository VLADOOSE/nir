package com.vladoose.nir.entity;

/**
 * Итог разбора файла ответа: PARSED — таблица найдена; NO_TABLE — Excel / Word без шапки с наименованием и ценой;
 * FAILED — не открылся (пароль, повреждён); TOO_LARGE — больше предела хранения, байтов нет; NOT_PARSED — PDF,
 * картинка, прочее или Excel / Word больше предела разбора: только просмотр и «Скачать».
 */
public enum ReplyParseStatus { PARSED, NO_TABLE, FAILED, TOO_LARGE, NOT_PARSED }
