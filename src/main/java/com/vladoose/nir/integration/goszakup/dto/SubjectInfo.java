package com.vladoose.nir.integration.goszakup.dto;

/** Организация площадки по БИН: название, телефон (бывает неполным), почта. */
public record SubjectInfo(String bin, String nameRu, String phone, String email) {}
