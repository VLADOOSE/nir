package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class DistributorResponse {
    private Long id;
    private String name;
    private String inn;
    private String address;
    private String lastName;
    private String firstName;
    private String middleName;
    private String phone;
    private String email;
    private String website;
    private List<EquipmentTypeResponse> equipmentTypes;
    private java.util.List<String> brands;
}
