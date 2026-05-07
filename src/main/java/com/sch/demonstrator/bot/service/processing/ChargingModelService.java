package com.sch.demonstrator.bot.service.processing;

import com.sch.demonstrator.bot.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChargingModelService {
    private static final double OPTIMAL_TEMP = 40.0;

    public List<Pair<Instant, Double>> computeChargingTable(double vehicleCapacityKwh, double maxVehiclePowerKw,
                                                            double infrastructurePowerKw, double startSoc, double targetSoc, Instant startTime) {

        log.info("Calculating charging table for request: ");
        log.info("Vehicle capacity kwh: {}", vehicleCapacityKwh);
        log.info("Max vehicle power kw: {}", maxVehiclePowerKw);
        log.info("Infrastructure power kw: {}", infrastructurePowerKw);
        log.info("Start soc: {}", startSoc);
        log.info("Target soc: {}", targetSoc);

        if (startSoc >= targetSoc) return new ArrayList<>();

        List<Pair<Instant, Double>> chargingTable = new ArrayList<>();

        double totalTimeSecs = 0.0;
        double currentSoc    = startSoc;
        double socStep       = 0.01;

        while (currentSoc < targetSoc) {
            double step      = Math.min(socStep, targetSoc - currentSoc);
            double energyKwh = step * vehicleCapacityKwh;

            double pKw = Math.max(
                    computeChargingIntegral(
                            vehicleCapacityKwh,
                            maxVehiclePowerKw,
                            infrastructurePowerKw,
                            currentSoc,
                            25.0),
                    1.0
            );

            pKw = Math.round(pKw * 100.0) / 100.0;
            if (chargingTable.isEmpty() || Double.compare(chargingTable.getLast().getValue(), pKw) != 0)
                chargingTable.add(new Pair<>(
                        startTime.plusSeconds((long) totalTimeSecs),
                        pKw
                ));

            totalTimeSecs += (energyKwh / pKw) * 3600.0;
            currentSoc    += step;
        }

        // Aggiungo un ultimo campionamento a 0 per concludere la ricarica
        chargingTable.add(new Pair<>(
                startTime.plusSeconds((long) totalTimeSecs),
                0.0
        ));

        return chargingTable;
    }

    public List<Pair<Long, Double>> computeChargingTable(double vehicleCapacityKwh, double maxVehiclePowerKw,
                                                         double infrastructurePowerKw, double startSoc, double targetSoc, long startTimeSeconds) {

        log.info("Calculating charging table for request: ");
        log.info("Vehicle capacity kwh: {}", vehicleCapacityKwh);
        log.info("Max vehicle power kw: {}", maxVehiclePowerKw);
        log.info("Infrastructure power kw: {}", infrastructurePowerKw);
        log.info("Start soc: {}", startSoc);
        log.info("Target soc: {}", targetSoc);

        if (startSoc >= targetSoc) return new ArrayList<>();

        List<Pair<Long, Double>> chargingTable = new ArrayList<>();

        double totalTimeSecs = 0.0;
        double currentSoc    = startSoc;
        double socStep       = 0.01;

        while (currentSoc < targetSoc) {
            double step      = Math.min(socStep, targetSoc - currentSoc);
            double energyKwh = step * vehicleCapacityKwh;

            double pKw = Math.max(
                    computeChargingIntegral(
                            vehicleCapacityKwh,
                            maxVehiclePowerKw,
                            infrastructurePowerKw,
                            currentSoc,
                            25.0),
                    1.0
            );

            pKw = Math.round(pKw * 100.0) / 100.0;
            if (chargingTable.isEmpty() || Double.compare(chargingTable.getLast().getValue(), pKw) != 0)
                chargingTable.add(new Pair<>(
                        startTimeSeconds + ((long) totalTimeSecs),
                        pKw
                ));

            totalTimeSecs += (energyKwh / pKw) * 3600.0;
            currentSoc    += step;
        }

        // Aggiungo un ultimo campionamento a 0 per concludere la ricarica
        chargingTable.add(new Pair<>(
                startTimeSeconds + ((long) totalTimeSecs),
                0.0
        ));

        return chargingTable;
    }

    /**
     * Calcola la potenza istantanea di ricarica basata sul modello sigmoide
     * @param infrastructurePowerKw potenza disponibile dalla colonnina (KW)
     * @param currentSoc SOC corrente (0.0 - 1.0)
     * @param currentTemp temperatura attuale batteria (°C)
     * @return potenza istantanea erogata in kW
     */
    private double computeChargingIntegral(double vehicleCapacityKwh, double maxVehiclePowerKw, double infrastructurePowerKw,
                                           double currentSoc, double currentTemp) {
        double maxPkw = Math.min(infrastructurePowerKw, maxVehiclePowerKw);
        double cRate = Math.min(vehicleCapacityKwh > 0 ?
                maxPkw / vehicleCapacityKwh : 1.0, 3.0);

        double socMid = Utils.clamp(0.75 - 0.05 * cRate, 0.55, 0.70);
        double steepness = 8.0 + 4.0 * cRate;
        double beta = cRate > 0 ? 0.05 / cRate : 0.0;

        double tempDiff = OPTIMAL_TEMP - currentTemp;
        double thermalDerating = 1.0 + 0.005 * tempDiff - 0.025 * Math.abs(tempDiff);
        double effectiveSocMid = socMid - 0.005 * tempDiff;

        double exponent = steepness * (currentSoc - effectiveSocMid);
        exponent = Utils.clamp(exponent, -500, 500); // evita overflow
        double sigmoidFactor = 1.0 / (1.0 + Math.exp(exponent));

        return maxPkw * thermalDerating * (beta + (1.0 - beta) * sigmoidFactor);
    }

}
