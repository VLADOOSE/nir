package com.vladoose.nir.dto.response;

import lombok.Data;

/**
 * Slim activity-apply projection used in nested references (without items),
 * to avoid recursion when serializing ApplyItemResponse.apply.
 */
@Data
public class ApplyShortResponse {
    private Long id;
    private String status;
}
