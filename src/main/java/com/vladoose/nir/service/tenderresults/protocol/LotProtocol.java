package com.vladoose.nir.service.tenderresults.protocol;

import com.vladoose.nir.entity.FailReason;

import java.util.List;

/** Итог лота по протоколу: участники, сколько подали и отклонено, победитель и второе место, причина провала, вывод. */
public record LotProtocol(String lotCode, List<ProtocolBid> bids, int bidsCount, int rejectedCount, String winnerBin, String secondBin,
                          FailReason failReason, String conclusion) {}
