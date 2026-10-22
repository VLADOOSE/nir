package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.util.ArrayList;
import java.util.List;

/** Накопитель итога лота при разборе. Победитель есть — причины провала нет. */
final class ProtocolBuilder {

    final String code;
    final List<ProtocolBid> bids = new ArrayList<>();
    Integer bidsCount;
    Integer rejectedCount;
    String winnerBin;
    String secondBin;
    String conclusion;
    FailReason failReason;

    ProtocolBuilder(String code) {
        this.code = code;
    }

    LotProtocol build() {
        int accepted = (int) bids.stream().filter(b -> !b.rejected()).count();
        int rejected = (int) bids.stream().filter(ProtocolBid::rejected).count();
        return new LotProtocol(code, List.copyOf(bids), bidsCount != null ? bidsCount : accepted,
                rejectedCount != null ? rejectedCount : rejected, winnerBin, secondBin, winnerBin != null ? null : failReason, conclusion);
    }
}
