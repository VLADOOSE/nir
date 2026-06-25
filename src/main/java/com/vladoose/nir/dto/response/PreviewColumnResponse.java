package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.LineField;
import lombok.Data;

@Data
public class PreviewColumnResponse {
    private int index;
    private String header;
    private LineField field;
}
