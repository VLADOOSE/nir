package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;

@Data
public class RegistryCandidateResponse {
    private String regNumber;
    private String name;
    private String producer;
    private String country;
    private LocalDate regDate;
    private LocalDate expirationDate;
    private Boolean unlimited;
    private Double score;
}
