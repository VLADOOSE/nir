package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PrivateRequestLinesAdd {
    @NotEmpty(message = "Нет позиций для добавления")
    @Size(max = 200, message = "За раз — не больше 200 позиций")
    @Valid
    private List<PrivateRequestLineInput> lines;
    /** пусто — APPEND */
    private LinesMode mode;
}
