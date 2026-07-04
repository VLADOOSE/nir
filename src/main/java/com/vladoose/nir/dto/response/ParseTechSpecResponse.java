package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class ParseTechSpecResponse {
    private TenderLotResponse lot;
    private boolean specFound;   // true на 200 (ошибки идут кодами 4xx/5xx)
    private boolean dimsFound;
    private boolean weightFound;
    private boolean ambiguous;   // имя лота в тендере не уникально — взят первый с файлом
    private String source;       // originalName файла техспеки
}
