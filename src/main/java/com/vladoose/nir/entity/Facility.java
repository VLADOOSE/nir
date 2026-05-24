package com.vladoose.nir.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

@Entity
@Table(name = "facility")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Название обязательно")
    @Size(max = 255)
    @Column(nullable = false, unique = true)
    private String name;

    @Pattern(regexp = "^(\\d{10}|\\d{12})$", message = "ИНН должен содержать 10 или 12 цифр")
    @Column(length = 12)
    private String inn;

    @Column(length = 500)
    private String address;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "middle_name", length = 100)
    private String middleName;

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String email;
}
