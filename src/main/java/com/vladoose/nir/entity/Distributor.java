package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "distributor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Distributor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 12, unique = true)
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

    @Column(length = 255)
    private String website;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "distributor_equipment_type",
        joinColumns = @JoinColumn(name = "distributor_id"),
        inverseJoinColumns = @JoinColumn(name = "equipment_type_id")
    )
    @Builder.Default
    private List<EquipmentType> equipmentTypes = new ArrayList<>();
}
