package com.vladoose.nir.service;

import com.vladoose.nir.exception.NotFoundException;

/** Картинки реквизитов: логотип бланка, печать, подпись директора. path — сегмент URL. */
public enum CompanyImageKind {
    LOGO("logo"), STAMP("stamp"), SIGNATURE("signature");

    private final String path;

    CompanyImageKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static CompanyImageKind fromPath(String path) {
        for (CompanyImageKind k : values()) if (k.path.equals(path)) return k;
        throw new NotFoundException("Нет такой картинки: " + path);
    }
}
