package com.vladoose.nir.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class ReportRequest {
    private String title;
    private String organization;
    private String text;
    private String toEmail; // optional, for sending
}
