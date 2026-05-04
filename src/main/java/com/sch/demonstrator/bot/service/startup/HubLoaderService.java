package com.sch.demonstrator.bot.service.startup;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.sch.demonstrator.bot.model.Charger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HubLoaderService {

    public Map<String, Map<String, Charger>> initHubs() {
        Map<String, Map<String, Charger>> hubs = new HashMap<>();

        log.info("=== Start loading hubs ===");
        try {
            //ClassPathResource resource = new ClassPathResource("csv/charging_hub_v2.csv");
            ClassPathResource resource = new ClassPathResource("csv/charging_hub_v2.csv");

            CsvMapper mapper = new CsvMapper();
            CsvSchema schema = CsvSchema.emptySchema().withHeader().withColumnSeparator(',');
            List<Map<String, String>> rows = new ArrayList<>();
            try (InputStream is = resource.getInputStream()) {
                MappingIterator<Map<String, String>> it = mapper
                        .readerFor(Map.class)
                        .with(schema)
                        .readValues(is);

                rows = it.readAll();
            }

            // Divido le righe per hub_name
            Map<String, List<Map<String,String>>> hubsGrouped = rows.stream()
                    .collect(Collectors.groupingBy(r -> r.get("hub_name").trim()));

            for (Map.Entry<String, List<Map<String,String>>> entry : hubsGrouped.entrySet()) {
                String hubId = entry.getKey();
                List<Map<String,String>> chargers = entry.getValue();

                if (!hubs.containsKey(hubId)) {
                    hubs.put(hubId, new HashMap<>());
                }

                hubs.putIfAbsent(hubId, new HashMap<>());
                Map<String, Charger> hubMap = hubs.get(hubId);
                int chargerCount = 1;
                for (Map<String,String> row : chargers) {
                    String chargerId = "%s_col%s".formatted(hubId, chargerCount);
                    Charger charger = new Charger(
                            hubId,
                            chargerId,
                            row.get("type").trim(),
                            Double.parseDouble(row.get("power_kw").trim()),
                            0.0,
                            true,
                            false
                    );

                    hubMap.put(chargerId, charger);
                    chargerCount++;
                }
            }

            log.info("Loaded {} hubs from CSV", hubs.size());

        } catch (IOException e) {
            log.error("Error loading hubs CSV", e);
        }

        return hubs;
    }
}
