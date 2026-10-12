package com.vladoose.nir.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Ровно одно из clientFacilityId / newClient. Строки — как у частной заявки. */
@Data
public class LeadConvertRequest {
    private Long clientFacilityId;
    @Valid private NewClient newClient;
    @Size(max = 5000) private String note;
    private List<PrivateRequestCreate.Line> lines;

    /** Пустое тело — «Создать заявку» одной кнопкой: клиент, контакт, текст и позиции берутся из обращения. */
    @JsonIgnore
    public boolean isOneClick() {
        return clientFacilityId == null && newClient == null && (lines == null || lines.isEmpty());
    }

    @Data
    public static class NewClient {
        @Size(max = 255) private String name;
        @Size(max = 50) private String phone;
        @Size(max = 255) private String email;
        @Size(max = 100) private String lastName;
        @Size(max = 100) private String firstName;
        @Size(max = 100) private String middleName;
    }
}
