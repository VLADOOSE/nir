package com.vladoose.nir.productwatch;

import com.vladoose.nir.integration.westmed.WestmedCatalogClient;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;

import java.util.ArrayList;
import java.util.List;

/** Управляемый фейк каталога сайта: без сети. */
public class FakeWestmedCatalogClient implements WestmedCatalogClient {
    public boolean admin = true;
    public List<WestmedCatalogItem> items = new ArrayList<>();
    public RuntimeException fail;
    public int adminCalls, publicCalls;

    @Override public boolean adminConfigured() { return admin; }
    @Override public List<WestmedCatalogItem> adminCatalog() { adminCalls++; if (fail != null) throw fail; return items; }
    @Override public List<WestmedCatalogItem> publicCatalog() { publicCalls++; if (fail != null) throw fail; return items; }
}
