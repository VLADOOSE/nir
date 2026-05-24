package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class AutoFillResponse {
    private int addedItems;
    private List<String> lotsWithoutResponse;
}
