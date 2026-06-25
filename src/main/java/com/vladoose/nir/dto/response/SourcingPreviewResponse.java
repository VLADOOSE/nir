package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class SourcingPreviewResponse {
    private List<SourcingGroupResponse> groups;
    private List<PrivateRequestLineResponse> unmatchedLines;
}
