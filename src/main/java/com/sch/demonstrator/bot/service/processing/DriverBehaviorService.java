package com.sch.demonstrator.bot.service.processing;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.sch.demonstrator.bot.model.QueueBehaviorParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Classe che modella il comportamento di un driver riguardo:
 * <ul>
 *     <li>L'accodamento all'hub di ricarica;</li>
 *     <li>La permanenza in coda;</li>
 *     <li>Il tempo di ricarica nel caso il connettore in uso sia differente da quello richiesta.</li>
 * </ul>
 *
 * La classe simula le decisioni per il traffico di background (esterno a sch) definito tramite BackgroundEvRequest.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DriverBehaviorService {

    /** Sensibilità alla lunghezza della coda. */
    private static final double ALPHA = 0.8;

    /** Peso dell'urgenza (soc basso) sulla soglia di abbandono della coda. */
    private static final double BETA_U = 0.6;

    /** Peso dell'urgenza per determinare il tempo massimo in coda. */
    private static final double URGENCY_WEIGHT = 0.5;

    /** Tempo medio di attesa in coda accettato. */
    private static final double WLAM_BASE_S = 30.0 * 60.0; // 30 minuti

    /** Tempo minimo di ricarica in caso di connettore degradato. */
    private static final long MIN_CHARGE_TIME = 10 * 60; // 10 minuti

    private final Random random = new Random();
    private final Map<Integer, QueueBehaviorParam> behaviorParams = new HashMap<>();



    public long computeMaxChargingTimeWithDegradedCharger(int hour, long totalChargeTime, double assignedChargerPower, double requestedChargerPower) {
        double impatienceCoefficient = behaviorParams.get(hour).getImpatienceCoefficient();
        double tolerance = behaviorParams.get(hour).getChargeToleranceCurvature();

        double frustrationFactor = Math.max(0.0, (requestedChargerPower - assignedChargerPower) / requestedChargerPower);

        long maxTimeAccepted = (long) (totalChargeTime * (1 - (impatienceCoefficient * Math.pow(frustrationFactor, tolerance))));
        return Math.max(MIN_CHARGE_TIME, maxTimeAccepted);
    }

    /**
     * Carica i parametri di comportamento definiti nel relativo csv e li divide per fascia orario.
     *
     * <p>Il file {@code driver_behavior_params.csv} contiene una riga per ogni ora del giorno, dove:
     * <ul>
     *   <li>{@code hour} rappresenta l'ora [0–23] associata ai parametri;</li>
     *   <li>{@code queue_tolerance} rappresenta la soglia di coda tollerata n_0 (numero di richiesta in attesa che precedono
     *       la richiesta presa in considerazione escludendo quelle in ricarica). Il valore più basso nelle ore di punta
     *       (meno tolleranza).</li>
     *   <li>{@code queue_frustration} rappresenta la shape della Weibull k. Influenza la velocità
     *       di crescita della frustrazione nel tempo di attesa nella coda (con k > 1 la frustrazione è crescente).</li>
     *   <li>{@code wait_tolerance_lambda} rappresenta il moltiplicatore wLamFactor che influenza il tempo medio di attesa
     *       massima in coda definito da {@link #WLAM_BASE_S}. Il moltiplicatore è più basso nelle ore di punta, riducendo
     *       il tempo massimo di attesa (wait_tolerance_lambda < 1).</li>
     * </ul>
     */
    @PostConstruct
    private void initQueueParams() {
        try {
            ClassPathResource resource = new ClassPathResource("csv/driver_behavior_params.csv");

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
                                Double.parseDouble(row.get("wait_tolerance_lambda")),
                                Double.parseDouble(row.get("impatience_coefficient")),
                                Double.parseDouble(row.get("charge_tolerance_curvature"))
                        )
                );
            }
        } catch (IOException e) {
            log.error("Error reading driver_behavior_params.csv", e);
        }

        for (Map.Entry<Integer, QueueBehaviorParam> entry : behaviorParams.entrySet()) {
            log.info("{}: {}", entry.getKey(), entry.getValue());
        }
    }


    /**
     * Determina se la richiesta si accoda all'hub di ricarica.
     *
     * @param queueLength numero di richieste attualmente in coda nell'hub (escluse quelle in ricarica)
     * @param currentSoc stato di carica della batteria in percentuale [0, 100]
     * @param arrivalTimeInternal tempo di arrivo nel tempo interno della simulazione (secondi)
     * @return {@code true} se l'utente si accoda, {@code false} se abbandona (balking)
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
     * Determina il tempo massimo in secondi che la richiesta attende in coda prima di abbandonare. Il tempo massimo è
     * calcolato tramite l'inversa della CDF di Weibull.
     *
     * <p>Distribuzione di Weibull(k, λ_eff):
     * <pre>
     *   h(t) = (k / λ) · (t / λ)^(k−1)
     * </pre>
     * dove:
     * <ul>
     *   <li>k controlla la velocità di crescita della frustrazione. Assume un valore più alto nelle ore di punta.</li>
     *   <li>λ_eff influenza la durata massima della tolleranza. Assume un valore minore nelle ore di punta ({@code wLamFactor < 1})
     *   e quando la batteria è scarica (u = 1).</li>
     * </ul>
     *
     * La distribuzione scelta tiene conto della frustrazione crescente al crescere dell'attesa: più tempo si resta in coda
     * più la probabilità di abbandonare aumenta.
     * </p>
     *
     * <p>CDF di Weibull(k, λ_eff):
     * <pre>
     *   P(t) = 1 − e^(−(t / λ)^k)
     * </pre>
     *
     * Ad esempio, con k=2 e λ=810 secondi, la probabilità che un utente abbia già abbandonato la coda entro 10 minuti
     * (600 secondi) è:
     * <pre>
     *   F(600)= 1 − e^(−(600 / 810)^2) = 0.41 -> 41%
     * </pre>
     * </p>
     *
     * <p>Formula per il campionamento (inversa della CDF):
     * <pre>
     *   T_abbandono = λ_eff · (−ln(1 − U))^(1/k)
     * </pre>
     * Si genera {@code U} uniforme [0,1] e lo si trasforma tramite la CDF inversa della Weibull.
     * </p>
     *
     * @param socPercent soc percentuale [0, 100] al momento dell'entrata in coda
     * @param arrivalTimeInternal tempo di arrivo nel tempo interno della simulazione (secondi)
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
        log.info("[QueueBehavior] Max accepted queue time: soc={}% k={} λ={}s giveUpAfter={}s ({} min)", socPercent, k, lam, tSeconds, tSeconds / 60.0);
        return Math.round(tSeconds);
    }

    /**
     * Calcola il livello di urgenza della richiesta in base allo stato della batteria.
     *
     * <p><b>Formula:</b>
     * <pre>
     *   u = 1 − soc / 100
     * </pre>
     * La formula traspone il soc in un valore di urgenza [0, 1], dove 0 rappresenta l'assenza di urgenza (batteria carica)
     * e 1 rappresenta l'urgenza massima (batteria scarica)
     *
     * @param currentSoc stato di carica in percentuale [0, 100]
     * @return urgenza normalizzata [0, 1]
     */
    private double computeUrgency(double currentSoc) {
        return 1.0 - (currentSoc / 100.0);
    }

    /**
     * Calcola la probabilità che la richiesta si accodi, applicando la sigmoide logistica con urgenza e fascia oraria.
     *
     <p>Formula:
     * <pre>
     *   P(accodarsi | n, u, h) = 1 / (1 + exp( α · (n − n_0(h)) · (1 − β_u · u) ))
     * </pre>
     * dove:
     * <ul>
     *   <li>n rappresenta le richieste attualmente in coda;</li>
     *   <li>n_0(h) rappresenta la soglia, in numero di veicoli, di coda tollerata per la fascia oraria h;</li>
     *   <li>u rappresenta l'urgenza di ricarica computata con {@link #computeUrgency};</li>
     *   <li>α rappresenta la sensibilità alla lunghezza della coda;</li>
     *   <li>β_u rappresenta il peso dell'urgenza sulla possibilità di non accodarsi.</li>
     * </ul>
     *
     * <p>La formula produce una probabilità che diminuisce continuamente all'aumentare della coda. Se n << n_0(h), la richiesta
     * si accoda quasi sempre, se n >> n_0(h) non si accoda quasi mai.
     * Il fattore di urgenza u incrementa la possibilità che la richiesta si accodi con batteria bassa anche se è presente coda.
     *
     * @param currentSoc soc in percentuale [0, 100]
     * @param queueLength numero di veicoli in coda (esclusi quelli in ricarica)
     * @param currentInternalTime tempo interno corrente (secondi)
     * @return probabilità di accodarsi [0, 1]
     */
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
