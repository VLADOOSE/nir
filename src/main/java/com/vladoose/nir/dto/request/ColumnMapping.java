package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.LineField;
import lombok.Data;

@Data
public class ColumnMapping {
    private String header;
    private LineField field;
}
