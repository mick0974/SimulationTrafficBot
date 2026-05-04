package com.sch.demonstrator.bot.service.processing;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.sch.demonstrator.bot.model.QueueBehaviorParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Service
@Slf4j
public class QueueBehaviorService {

    /** Sensibilità alla lunghezza della coda. */
    private static final double ALPHA = 0.8;

    /** Peso dell'urgenza (SoC basso) sulla soglia di abbandono della coda. */
    private static final double BETA_U = 0.6;

    /** Peso dell'urgenza per determinare il tempo massimo in coda. */
    private static final double URGENCY_WEIGHT = 0.5;

    /** Tempo medio di attesa in coda accettato. */
    private static final double WLAM_BASE_S = 18.0 * 60.0; // 30 minuti

    private final Random random = new Random();
    private final Map<Integer, QueueBehaviorParam> behaviorParams = new HashMap<>();

    @PostConstruct
    private void initQueueParams() {
        try {
            ClassPathResource resource = new ClassPathResource("csv/queue_behavior_params.csv");

            // Converto il csv in una lista di mappe. Ogni mappa contiene una riga
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

            for (Map<String, String> row : rows) {
                behaviorParams.put(
                        Integer.parseInt(row.get("hour")),
                        new QueueBehaviorParam(
                                Double.parseDouble(row.get("queue_tolerance")),
                                Double.parseDouble(row.get("queue_frustration")),
                                Double.parseDouble(row.get("wait_tolerance_lambda"))
                        )
                );
            }
        } catch (IOException e) {
            log.error("Error reading queue_behavior_params.csv", e);
        }

        for (Map.Entry<Integer, QueueBehaviorParam> entry : behaviorParams.entrySet()) {
            log.info("{}: {}", entry.getKey(), entry.getValue());
        }
    }


    /**
     * Determina se l'utente entra in coda per attendere una ricarica
     *
     * @return true se l'utente si accoda, false se l'utente abbandona la ricarica
     */
    public boolean joinQueue(int queueLength, double currentSoc, long arrivalTimeInternal) {
        double urgency = computeUrgency(currentSoc);
        double pJoin = computeProbabilityJoiningQueue(currentSoc, queueLength, arrivalTimeInternal);
        boolean joins = random.nextDouble() < pJoin;

        log.info("[QueueBehavior] Entering queue: queue={} SoC={}% hour={} u={} P(join)={}% = enters: {}",
                queueLength, currentSoc,
                getInternalHour(arrivalTimeInternal),
                urgency, pJoin * 100,
                joins);

        return joins;
    }

    /**
     * Determina il tempo massimo in secondi che l'utente è disposto ad attendere in coda
     * prima di abbandonare.
     *
     * Distribuzione: Weibull(k, λ_eff) con inversione della CDF:
     *   Tempo_abbandono_coda = λ_eff · (−ln(1 − U))^(1/k)) U ~ Uniform(0,1)

     * @param socPercent  percentuale di carica della batteria del veicolo al tempo dell'entrata in coda
     * @param arrivalTimeInternal Tempo di arrivo nel tempo interno
     * @return tempo di abbandono in secondi
     */
    public long getMaxQueueingTime(double socPercent, long arrivalTimeInternal) {
        int hour = getInternalHour(arrivalTimeInternal);
        QueueBehaviorParam params = behaviorParams.getOrDefault(hour, behaviorParams.get(0));

        double urgency = computeUrgency(socPercent);
        double k = params.getQueueFrustration();
        double baseLambda = params.getWaitToleranceLambda();

        double lam = WLAM_BASE_S * baseLambda * (1.0 - URGENCY_WEIGHT * urgency);
        lam = Math.max(1e-6, lam);

        double u = random.nextDouble();
        u = Math.min(u, 1.0 - 1e-12);

        double tSeconds = lam * Math.pow(-Math.log(1.0 - u), 1.0 / k);
        log.info("[QueueBehavior] Max accepted queue time: SoC={}% k={} λ={}s giveUpAfter={}s ({} min)", socPercent, k, lam, tSeconds, tSeconds / 60.0);
        return Math.round(tSeconds);
    }

    private double computeUrgency(double currentSoc) {
        return 1.0 - (currentSoc / 100.0);
    }

    private double computeProbabilityJoiningQueue(double currentSoc, int queueLength, long currentInternalTime) {
        int hour = getInternalHour(currentInternalTime);
        QueueBehaviorParam params = behaviorParams.getOrDefault(hour, behaviorParams.get(0));

        double n0 = params.getQueueTolerance();
        double urgency = computeUrgency(currentSoc);
        double urgFactor = 1.0 - BETA_U * urgency;

        double exponent = ALPHA * (queueLength - n0) * urgFactor;
        return 1.0 / (1.0 + Math.exp(exponent));
    }

    private int getInternalHour(long internaTime) {
        return Long.valueOf(internaTime / 3600).intValue();
    }

}
