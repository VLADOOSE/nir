package com.vladoose.nir.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class SourcingGroupResponse {
    private DistributorResponse distributor;
    private List<PrivateRequestLineResponse> lines;
}
