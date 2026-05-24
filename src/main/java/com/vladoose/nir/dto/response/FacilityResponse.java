package com.vladoose.nir.dto.response;

import lombok.Data;

@Data
public class FacilityResponse {
    private Long id;
    private String name;
    private String inn;
    private String address;
    private String lastName;
    private String firstName;
    private String middleName;
    private String phone;
    private String email;
}
