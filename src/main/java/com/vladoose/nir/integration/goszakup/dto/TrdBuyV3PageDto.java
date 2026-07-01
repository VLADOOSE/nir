package com.vladoose.nir.integration.goszakup.dto;

import lombok.Data;

import java.util.List;

/** Страница v3 GraphQL TrdBuy: пагинация по after=lastId (id DESC), nextAfter == null → конец. */
@Data
public class TrdBuyV3PageDto {
    private List<TrdBuyDto> items;
    private Long nextAfter;
}
