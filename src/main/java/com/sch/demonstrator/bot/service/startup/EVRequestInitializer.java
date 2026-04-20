package com.sch.demonstrator.bot.service.startup;

import com.sch.demonstrator.bot.model.BackgroundEVRequest;

import java.util.List;

public interface EVRequestInitializer {
    List<BackgroundEVRequest> generateRequests(String[] hubIds);
}
