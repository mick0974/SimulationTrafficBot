package com.sch.demonstrator.bot.service.startup;

import com.sch.demonstrator.bot.model.BackgroundEVRequest;

import java.util.List;
import java.util.Map;

public interface EVRequestInitializer {
    List<BackgroundEVRequest> generateRequests(Map<String, Map<String, Double>> hubs);
}
