package com.sch.demonstrator.bot.service.startup;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.sch.demonstrator.bot.config.BotProperties;
import com.sch.demonstrator.bot.model.ArrivalRecord;
import com.sch.demonstrator.bot.model.BackgroundEVRequest;
import com.sch.demonstrator.bot.model.EVModel;
import com.sch.demonstrator.bot.model.GaussianParamRecord;
import com.sch.demonstrator.bot.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "request.mode", havingValue = "generation", matchIfMissing = true)
public class EVRequestGenerator implements EVRequestInitializer {

    private final BotProperties props;

    private final Random random = new Random();
    private final CsvMapper mapper = new CsvMapper();

    @Override
    public List<BackgroundEVRequest> generateRequests(Map<String, Map<String, Double>> hubData) {
        log.info("=== Start generating EV requests ===");

        List<EVModel> evModels = loadVehicleDataset();
        Map<String, Map<Integer, Integer>> arrivals = loadArrivalPerHub();
        String[] hubIds = hubData.keySet().toArray(new String[0]);

        Map<Integer, Integer> totalPerHour = computeTotalRequestsPerHour(arrivals);
        log.debug("Mean requests per hour: {}", totalPerHour);

        Map<Integer, Map<String, Double>> hubDistribution =
                computeHubDistribution(arrivals, totalPerHour);

        Map<String, Map<Integer, Pair<Double, Double>>> gaussianParams =
                loadGaussianParams();

        NormalDistribution socTargetDist = new NormalDistribution(
                props.getGeneration().getSoc().getTarget().getMean(),
                props.getGeneration().getSoc().getTarget().getStdDev());

        List<BackgroundEVRequest> requests = new ArrayList<>();
        for (int currentHour = props.getGeneration().getStartHour(); currentHour < props.getGeneration().getEndHour(); currentHour++) {
            int totalRequests = totalPerHour.getOrDefault(currentHour, 0);

            if (totalRequests == 0) continue;

            double lambdaPerSecond = totalRequests / 3600.0;

            int currentTime = currentHour * 3600;
            int endTime = (currentHour + 1) * 3600;

            while (currentTime < endTime) {

                currentTime += generateDeltaTime(lambdaPerSecond);
                if (currentTime >= endTime) break;

                // Determino a che hub assegnare la richiesta
                String hubId;
                Map<String, Double> probs = hubDistribution.get(currentHour);

                if (probs != null && !probs.isEmpty()) {
                    hubId = sampleHub(probs);
                } else {
                    hubId = hubIds[random.nextInt(hubIds.length)];
                }

                EVModel evModel = evModels.get(random.nextInt(evModels.size()));
                String requestedChargerType = selectChargerType(evModel, hubData.get(hubId).keySet());
                log.debug("Requested charger type: {}", requestedChargerType);
                log.debug("hub stats: {}", hubData.get(hubId));
                double expectedChargerPower = hubData.get(hubId).get(requestedChargerType);

                // Computo la gaussiana per campionare la soc iniziale
                Pair<Double, Double> params = gaussianParams.get(hubId).get(currentHour);

                NormalDistribution socInitDist = new NormalDistribution(
                        params.getFirst(),
                        params.getSecond());

                double socOrigin = round(Math.max(0.05, socInitDist.sample()));

                double socTarget;
                do {
                    socTarget = round(socTargetDist.sample());
                } while (socTarget <= socOrigin);

                BackgroundEVRequest request = BackgroundEVRequest.builder()
                        .requestId(UUID.randomUUID())
                        .vehicleCapacityKwh(evModel.batteryCapacityKWh())
                        .maxVehiclePowerKw(evModel.fastChargingPowerKwDc())
                        .startSoc(round(socOrigin))
                        .targetSoc(round(socTarget))
                        .chargerType(requestedChargerType)
                        .expectedChargerPower(expectedChargerPower)
                        .startTimeSeconds(currentTime)
                        .hubId(hubId)
                        .hour(currentHour)
                        .formattedTime(Utils.formatTime(currentTime))
                        .build();

                requests.add(request);
            }
        }

        log.info("Generated {} EVRequests. Saving csv...", requests.size());
        writeToCsv(requests);
        log.info("CSV saved successfully");

        return requests;
    }

    private Map<Integer, Integer> computeTotalRequestsPerHour(
            Map<String, Map<Integer, Integer>> arrivalsPerHub) {

        Map<Integer, Integer> total = new HashMap<>();

        for (Map<Integer, Integer> hubData : arrivalsPerHub.values()) {
            for (Map.Entry<Integer, Integer> e : hubData.entrySet()) {
                total.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }

        return total;
    }

    private Map<Integer, Map<String, Double>> computeHubDistribution(
            Map<String, Map<Integer, Integer>> arrivalsPerHub,
            Map<Integer, Integer> totalPerHour) {

        Map<Integer, Map<String, Double>> distribution = new HashMap<>();

        for (String hub : arrivalsPerHub.keySet()) {
            for (Map.Entry<Integer, Integer> e : arrivalsPerHub.get(hub).entrySet()) {

                int hour = e.getKey();
                int arrivals = e.getValue();
                int total = totalPerHour.getOrDefault(hour, 0);

                if (total == 0) continue;

                double prob = (double) arrivals / total;

                distribution
                        .computeIfAbsent(hour, h -> new HashMap<>())
                        .put(hub, prob);
            }
        }

        return distribution;
    }

    private String sampleHub(Map<String, Double> probabilities) {
        double r = random.nextDouble();
        double cumulative = 0.0;

        for (Map.Entry<String, Double> e : probabilities.entrySet()) {
            cumulative += e.getValue();
            if (r <= cumulative) return e.getKey();
        }

        return probabilities.keySet().iterator().next();
    }

    private int generateDeltaTime(double lambdaPerSecond) {
        double u = random.nextDouble();
        return (int) Math.max(1, -Math.log(1 - u) / lambdaPerSecond);
    }

    private String selectChargerType(EVModel model, Set<String> hubChargerType) {
        log.debug("Selecting charger type between {} for model plug {}", hubChargerType, model.fastChargePort());
        if (hubChargerType.isEmpty()
                || model.fastChargePort() == null
                || model.fastChargePort().isEmpty()
                || !hubChargerType.contains(model.fastChargePort()))
            return "AC";

        return random.nextBoolean() ? "AC" : model.fastChargePort();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // -------------------- CSV LOADERS --------------------

    private List<EVModel> loadVehicleDataset() {
        List<EVModel> evModels = new ArrayList<>();
        try {
            ClassPathResource res = new ClassPathResource("csv/ev-dataset.csv");
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            ObjectReader reader = mapper.readerFor(EVModel.class).with(schema);
            try (MappingIterator<EVModel> it = reader.readValues(res.getInputStream())) {
                evModels.addAll(it.readAll());
            }
        } catch (IOException e) {
            log.error("Error reading vehicle dataset", e);
        }
        return evModels;
    }

    private Map<String, Map<Integer, Integer>> loadArrivalPerHub() {
        Map<String, Map<Integer, Integer>> map = new HashMap<>();

        try {
            ClassPathResource res = new ClassPathResource("csv/arrivals_per_hub.csv");
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            ObjectReader reader = mapper.readerFor(ArrivalRecord.class).with(schema);

            try (MappingIterator<ArrivalRecord> it = reader.readValues(res.getInputStream())) {
                while (it.hasNext()) {
                    ArrivalRecord r = it.next();

                    map.computeIfAbsent(r.getHubId(), k -> new HashMap<>())
                            .put((int) r.getHour(), r.getArrivals());
                }
            }

        } catch (IOException e) {
            log.error("Error reading arrivals csv", e);
        }

        return map;
    }

    private Map<String, Map<Integer, Pair<Double, Double>>> loadGaussianParams() {
        Map<String, Map<Integer, Pair<Double, Double>>> params = new HashMap<>();

        try {
            ClassPathResource res = new ClassPathResource("csv/soc_gaussian_params.csv");
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            ObjectReader reader = mapper.readerFor(GaussianParamRecord.class).with(schema);

            try (MappingIterator<GaussianParamRecord> it = reader.readValues(res.getInputStream())) {
                while (it.hasNext()) {
                    GaussianParamRecord r = it.next();

                    params.computeIfAbsent(r.getHubId(), k -> new HashMap<>())
                            .put((int) r.getHour(), new Pair<>(r.getMuInit(), r.getSigmaInit()));
                }
            }

        } catch (IOException e) {
            log.error("Error reading gaussian params csv", e);
        }

        return params;
    }

    // -------------------- CSV OUTPUT --------------------

    private void writeToCsv(List<BackgroundEVRequest> generated) {
        try {
            Path path = Paths.get(props.getGeneration().getGeneratedRequestOutput());
            Files.createDirectories(path.getParent());

            try (Writer writer = new FileWriter(path.toFile())) {
                new StatefulBeanToCsvBuilder<BackgroundEVRequest>(writer)
                        .withQuotechar('"')
                        .build()
                        .write(generated);
            }

        } catch (Exception e) {
            log.error("Error saving CSV", e);
        }
    }
}