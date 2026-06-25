package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class ImportPreviewResponse {
    private List<PreviewColumnResponse> columns;
    private List<List<String>> rows;
}
