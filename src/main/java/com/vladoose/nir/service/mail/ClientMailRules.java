package com.vladoose.nir.service.mail;

import java.util.List;

/**
 * Правила ящика info@: адрес уведомлений сайта, свой домен (домен ящика), служебные домены — шаблоны «2gis.ru»
 * (домен и поддомены) и «*.mail.ru» (только поддомены). Всё нижним регистром.
 */
public record ClientMailRules(String siteNotificationFrom, String ownDomain, List<String> serviceDomains) {}
