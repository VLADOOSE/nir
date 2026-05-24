package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DistributorRequest {

    @NotBlank(message = "Название обязательно")
    @Size(max = 255)
    private String name;

    @Pattern(regexp = "^(\\d{10}|\\d{12})$", message = "ИНН должен содержать 10 или 12 цифр")
    private String inn;

    private String address;
    private String lastName;
    private String firstName;
    private String middleName;
    private String phone;
    private String email;
    private String website;
    private List<Long> equipmentTypeIds;
}
