package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LeadChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Ручное обращение: звонок, WhatsApp или другое. Телефон или email обязателен — проверяет сервис. */
@Data
public class LeadCreateRequest {
    private LeadChannel channel;
    @Size(max = 255) private String contactName;
    @Size(max = 50) private String contactPhone;
    @Size(max = 255) private String company;
    @Size(max = 255) private String contactEmail;
    @Size(max = 5000) private String message;
    @Valid private List<LeadItemDto> items;
}
