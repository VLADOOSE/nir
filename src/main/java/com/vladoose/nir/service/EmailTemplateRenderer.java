package com.vladoose.nir.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Подстановка {{плейсхолдеров}} в шаблон письма. Неизвестные метки остаются как есть. */
@Component
public class EmailTemplateRenderer {

    public String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{{" + e.getKey() + "}}", value);
        }
        return out;
    }
}
