package com.vladoose.nir.integration.nbrk;

import java.time.LocalDate;
import java.util.List;

/** Курсы НБ РК на дату (спека §4.2); сбой — UpstreamException со своим текстом. */
public interface NbrkRatesClient {
    List<NbrkRate> fetch(LocalDate date);
}
