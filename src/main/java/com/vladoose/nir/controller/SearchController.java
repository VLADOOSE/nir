package com.vladoose.nir.controller;

import com.vladoose.nir.dto.TenderSearchRequest;
import com.vladoose.nir.dto.TenderSearchResult;
import com.vladoose.nir.service.SearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "http://localhost:5173")
public class SearchController {
    private final SearchService svc;
    public SearchController(SearchService svc){ this.svc = svc; }

    @PostMapping("/tenders")
    public List<TenderSearchResult> search(@RequestBody TenderSearchRequest req) {
        return svc.search(req);
    }
}
