package com.vladoose.nir.dto.request;

import com.vladoose.nir.dto.response.ProductGroupDto;

import java.util.List;

/** Весь список групп рынка по порядку (спека 2026-10-19-product-watch §12.1): чего нет в списке — удаляется. */
public record ProductGroupsRequest(List<ProductGroupDto> groups) {}
