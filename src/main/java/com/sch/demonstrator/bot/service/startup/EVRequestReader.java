package com.sch.demonstrator.bot.service.startup;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "request.mode", havingValue = "upload", matchIfMissing = false)
public class EVRequestReader implements EVRequestInitializer {

    private final BotProperties props;

    @Override
    public List<BackgroundEVRequest> generateRequests(String[] hubIds) {
        log.info("=== Start uploading EV requests ===");

        CsvMapper mapper = new CsvMapper();
        List<BackgroundEVRequest> evRequests = new ArrayList<>();

        // Carico e converto i modelli EV dal csv
        try {
            File requestCSV = new File(props.getUpload().getInput());
            if (!requestCSV.exists()) {
                log.error("CSV file not found: {}", requestCSV.getAbsolutePath());
                return List.of();
            }

            CsvSchema schema = CsvSchema.emptySchema().withHeader().withColumnSeparator(',');
            ObjectReader reader = mapper.readerFor(BackgroundEVRequest.class).with(schema);
            try (MappingIterator<BackgroundEVRequest> iterator = reader.readValues(requestCSV)) {
                evRequests.addAll(iterator.readAll());
            }
        } catch (IOException e) {
            log.error("Error reading vehicle csv dataset file", e);
        }

        return evRequests;
    }
}
