package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

public record AssignWinnerResponse(
        Long applyId, Long applyItemId, Long lotId, String distributorName, BigDecimal offeredCost) {}
