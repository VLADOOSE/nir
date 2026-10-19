package com.vladoose.nir.dto.request;

import lombok.Data;

/** Отметка «дата + заметка»: on — «ГГГГ-ММ-ДД», пусто — снять отметку. */
@Data
public class ExecutionMarkRequest {
    private String on;
    private String note;
}
