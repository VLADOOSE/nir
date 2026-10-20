package com.vladoose.nir.dto.response;

import java.util.List;

/** «Проверить» группу: сколько нашла площадка с идущим приёмом, сколько прошло, причины (сначала прошедшие). */
public record ProbeResponse(int found, int passed, List<Lot> lots) {
    public record Lot(String numberAnno, String lotName, String announcement, String customer, String deadlineAt, boolean passed,
                      String reason) {}
}
