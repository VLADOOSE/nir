package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Свои ключи и подпись нового ключа по умолчанию — из User-Agent этого же запроса. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyListResponse {
    private List<PasskeyResponse> keys = new ArrayList<>();
    private String suggestedLabel;
}
