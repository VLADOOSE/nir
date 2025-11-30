package com.vladoose.nir.controller;

import com.vladoose.nir.dto.ActivityApplyRequest;
import com.vladoose.nir.entity.Activity;
import com.vladoose.nir.service.ActivityService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/activity")
@CrossOrigin(origins = "http://localhost:5173")
public class ActivityController {
    private final ActivityService svc;
    public ActivityController(ActivityService svc){ this.svc = svc; }

    @PostMapping("/apply")
    public Activity apply(@RequestBody ActivityApplyRequest req) throws Exception {
        return svc.save(req);
    }
}
