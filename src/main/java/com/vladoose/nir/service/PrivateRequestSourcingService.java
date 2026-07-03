package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PrivateRequestLineResponse;
import com.vladoose.nir.dto.response.SourcingGroupResponse;
import com.vladoose.nir.dto.response.SourcingPreviewResponse;
import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.mapper.DistributorMapper;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Группирует строки частной заявки по поставщикам, у кого есть бренд строки (case-insensitive). */
@Service
public class PrivateRequestSourcingService {

    private final PrivateRequestService privateRequestService;
    private final DistributorService distributorService;
    private final DistributorMapper distributorMapper;

    public PrivateRequestSourcingService(PrivateRequestService privateRequestService,
                                         DistributorService distributorService,
                                         DistributorMapper distributorMapper) {
        this.privateRequestService = privateRequestService;
        this.distributorService = distributorService;
        this.distributorMapper = distributorMapper;
    }

    public SourcingPreviewResponse buildSourcing(Long privateRequestId) {
        List<PrivateRequestLineResponse> lines = privateRequestService.linesWithRegistration(privateRequestId);
        List<Distributor> distributors = distributorService.findAll(); // скоуплен рынком

        Map<Long, SourcingGroupResponse> byDistributor = new LinkedHashMap<>();
        List<PrivateRequestLineResponse> unmatched = new ArrayList<>();

        for (PrivateRequestLineResponse line : lines) {
            List<Distributor> matching = new ArrayList<>();
            for (Distributor d : distributors) {
                if (BrandMatch.firstCarried(d.getBrands(), line.getManufact()) != null) {
                    matching.add(d);
                }
            }
            if (matching.isEmpty()) {
                unmatched.add(line);
                continue;
            }
            for (Distributor d : matching) {
                SourcingGroupResponse g = byDistributor.computeIfAbsent(d.getId(), k -> {
                    SourcingGroupResponse ng = new SourcingGroupResponse();
                    ng.setDistributor(distributorMapper.toResponse(d));
                    ng.setLines(new ArrayList<>());
                    return ng;
                });
                g.getLines().add(line);
            }
        }

        SourcingPreviewResponse preview = new SourcingPreviewResponse();
        preview.setGroups(new ArrayList<>(byDistributor.values()));
        preview.setUnmatchedLines(unmatched);
        return preview;
    }
}
