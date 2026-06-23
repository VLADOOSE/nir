package com.vladoose.nir.service;

import lombok.Data;

/** Запись slim-дампа реестра РК (ключи как в rk-mi-registry-full.json). */
@Data
public class RegistryDumpRecord {
    private String reg;
    private String name;
    private String producer;
    private String country;
    private String regDate;
    private String exp;
    private Boolean unlimited;
}
