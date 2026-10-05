package com.vladoose.nir.service.mail;

/**
 * Настройки классификации: адрес отправки КП («своё письмо»), адрес уведомлений сайта (нижним регистром, "" — правило
 * выключено) и считать ли Excel без метки письмом клиники (ящик info@ — да, ящик закупок zakup@ — нет).
 */
public record ClassifierRules(String ownAddress, String siteNotificationFrom, boolean clientRequests) {}
