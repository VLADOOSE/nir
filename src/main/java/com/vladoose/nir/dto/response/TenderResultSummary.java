package com.vladoose.nir.dto.response;

/** Итог тендера для списка: чип, «звонить» (никто не подал / ОИ сорвался), состоялся, мы участвовали. */
public record TenderResultSummary(String chip, boolean call, boolean done, boolean ours) {}
