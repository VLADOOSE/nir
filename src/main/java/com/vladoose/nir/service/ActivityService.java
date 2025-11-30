package com.vladoose.nir.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.dto.ActivityApplyRequest;
import com.vladoose.nir.entity.Activity;
import com.vladoose.nir.repository.ActivityRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class ActivityService {
    private final ActivityRepository repo;
    private final ObjectMapper mapper;

    public ActivityService(ActivityRepository repo, ObjectMapper mapper) {
        this.repo = repo; this.mapper = mapper;
    }

    public Activity save(ActivityApplyRequest req) throws Exception {
        Activity a = new Activity();
        a.setTenderId(req.getTenderId());
        a.setFacilityId(req.getFacilityId());
        String itemsJson = mapper.writeValueAsString(req.getItems());
        a.setItemsJson(itemsJson);
        a.setCreatedAt(OffsetDateTime.now());
        return repo.save(a);
    }
}
