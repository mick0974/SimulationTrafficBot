package com.sch.demonstrator.bot.service.processing;

import com.sch.demonstrator.bot.event.ChargerFreedEvent;
import com.sch.demonstrator.bot.event.ChargingEndedEvent;
import com.sch.demonstrator.bot.event.ChargingProgressEvent;
import com.sch.demonstrator.bot.model.Charger;
import com.sch.demonstrator.bot.service.exception.ChargerInUseException;
import com.sch.demonstrator.bot.service.exception.ChargerNotFoundException;
import com.sch.demonstrator.bot.service.exception.ChargerOperationInvalidException;
import com.sch.demonstrator.bot.service.startup.HubLoaderService;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@Slf4j
@RequiredArgsConstructor
public class HubManagementService {
    private final HubLoaderService hubLoaderService;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final Map<String, Map<String, Charger>> hubs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> hubLocks = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, Map<String, Double>> hubStats = new HashMap<>();

    @PostConstruct
    public void init() {
        hubs.putAll(hubLoaderService.initHubs());

        for (Map.Entry<String, Map<String, Charger>> hub : hubs.entrySet()) {
            Map<String, Double> hubStat = new HashMap<>();
            for (Charger charger : hub.getValue().values()) {
                hubStat.putIfAbsent(charger.getType(), charger.getInfrastructurePowerKw());
            }

            hubStats.put(hub.getKey(), hubStat);
        }

        hubStats.forEach((hubId, stats) -> {
            System.out.println("Hub: " + hubId);

            stats.forEach((key, value) ->
                    System.out.println("  " + key + " = " + value)
            );
        });
    }

    /**
     * Seleziona e assegna un connettore disponibile all'interno dell'hub specificato per una richiesta di tipo Background.
     * Una richiesta di tipo Background può essere assegnata ad un qualsiasi tipo di connettore, se necessario.
     * La selezione avviene secondo la seguente strategia:
     * <ol>
     *     <li>
     *         Tra i connettori disponibili viene selezionato quello che:
     *         <ul>
     *             <li>ha tipo uguale a {@code chargerType}</li>
     *             <li>ha potenza {@code >= maxVehiclePowerKw}</li>
     *         </ul>
     *         Tra questi viene scelto quello con la potenza minima (più vicina alla richiesta).
     *     </li>
     *
     *     <li>
     *         Se nessun connettore soddisfa entrambi i vincoli,
     *         viene selezionato un connettore disponibile con potenza {@code >= maxVehiclePowerKw},
     *         indipendentemente dal tipo, scegliendo quello con potenza minima.
     *     </li>
     *
     *     <li>
     *         Se nessun connettore soddisfa il vincolo di potenza minima,
     *         viene selezionato il connettore disponibile con potenza massima.
     *     </li>
     * </ol>
     *
     * Una volta selezionato, il connettore viene marcato come occupato.
     *
     * @param hubTarget identificativo dell'hub su cui effettuare la ricerca
     * @param chargerType tipo di connettore richiesto
     * @param maxVehiclePowerKw potenza massima supportata dal veicolo
     * @return il {@link Charger} assegnato oppure {@code null} se non ci sono connettori disponibili
     */
    public Charger assignChargerBackground(String hubTarget, String chargerType, double maxVehiclePowerKw) {
        synchronized (getLock(hubTarget)) {
            Set<Charger> availableChargers = filterAvailableChargers(hubTarget);

            if (availableChargers.isEmpty()) return null;

            Charger bestMatch = null; // connettore del tipo chargerType e con potenza minore tra i connettore disponibili con potenza >= a maxVehiclePowerKw
            Charger leastAbovePower = null; // connettore con potenza minore tra i connettore disponibili con potenza >= a maxVehiclePowerKw
            Charger maxAvailablePower = null; // connettore con potenza erogabile maggiore tra i rimanenti

            for (Charger c : availableChargers) {
                double power = c.getInfrastructurePowerKw();

                // Aggiorno se è presente un connettore libero con potenza erogabile maggiore
                if (maxAvailablePower == null ||
                        power > maxAvailablePower.getInfrastructurePowerKw()) {
                    maxAvailablePower = c;
                }

                // Aggiorno se il connettore eroga potenza >= a quella ricevibile dal veicolo ed eroga una potenza minore a quello salvato
                // (non spreco connettori con potenza superiore che possono essere dati a veicoli con maxVehiclePowerKw superiore)
                if (power >= maxVehiclePowerKw) {
                    if (leastAbovePower == null ||
                            power < leastAbovePower.getInfrastructurePowerKw()) {
                        leastAbovePower = c;
                    }

                    // Aggiorno il candidato migliore
                    if (c.getType().equals(chargerType)) {
                        if (bestMatch == null ||
                                power < bestMatch.getInfrastructurePowerKw()) {
                            bestMatch = c;
                        }
                    }
                }
            }

            Charger selected = bestMatch != null
                    ? bestMatch
                    : (leastAbovePower != null ? leastAbovePower : maxAvailablePower);

            if (selected == null) return null;

            selected.setOccupied(true);
            return selected;
        }
    }

    /**
     * Seleziona e assegna un connettore disponibile all'interno dell'hub specificato per una richiesta di tipo Adas.
     * Una richiesta di tipo Adas può essere assegnata solo ad un connettore del tipo richiesto.
     * La selezione avviene secondo la seguente strategia:
     * <ol>
     *     <li>
     *         Tra i connettori disponibili viene selezionato quello che:
     *         <ul>
     *             <li>ha tipo uguale a {@code chargerType}</li>
     *             <li>ha potenza {@code >= maxVehiclePowerKw}</li>
     *         </ul>
     *         Tra questi viene scelto quello con la potenza minima (più vicina alla richiesta).
     *     </li>
     *
     *     <li>
     *         Se nessun connettore soddisfa entrambi i vincoli,
     *         viene selezionato un connettore disponibile con potenza maggiore tra quelli del tipo richiesto.
     *     </li>
     * </ol>
     *
     * Una volta selezionato, il connettore viene marcato come occupato.
     *
     * @param hubTarget identificativo dell'hub su cui effettuare la ricerca
     * @param chargerType tipo di connettore richiesto
     * @param maxVehiclePowerKw potenza massima supportata dal veicolo
     * @return il {@link Charger} assegnato oppure {@code null} se non ci sono connettori disponibili
     */
    public Charger assignChargerAdas(String hubTarget, String chargerType, double maxVehiclePowerKw) {
        synchronized (getLock(hubTarget)) {
            Set<Charger> availableChargers = filterAvailableChargers(hubTarget);

            if (availableChargers.isEmpty()) return null;

            Charger bestMatch = null; // connettore del tipo chargerType e con potenza minore tra i connettore disponibili con potenza >= a maxVehiclePowerKw
            Charger maxAvailablePower = null; // connettore con potenza erogabile maggiore tra i connettori del tipo scelto.

            for (Charger c : availableChargers) {
                double power = c.getInfrastructurePowerKw();

                // Aggiorno se è presente un connettore libero con potenza erogabile maggiore
                if (maxAvailablePower == null ||
                        power > maxAvailablePower.getInfrastructurePowerKw()) {
                    maxAvailablePower = c;
                }

                // Aggiorno se il connettore eroga potenza >= a quella ricevibile dal veicolo ed eroga una potenza minore a quello salvato
                // (non spreco connettori con potenza superiore che possono essere dati a veicoli con maxVehiclePowerKw superiore)
                if (power >= maxVehiclePowerKw && c.getType().equals(chargerType)) {
                    if (bestMatch == null ||
                            power < bestMatch.getInfrastructurePowerKw()) {
                        bestMatch = c;
                    }
                }
            }

            Charger selected = bestMatch != null
                    ? bestMatch
                    : maxAvailablePower;

            if (selected == null) return null;

            selected.setOccupied(true);
            return selected;
        }
    }

    public void deactivateCharger(String chargerId) {
        Charger charger = getCharger(chargerId);
        if (charger == null)
            throw new ChargerNotFoundException("Could not find charger with id " + chargerId);

        synchronized (getLock(charger.getHubId())) {
            if (!charger.isActive())
                throw new ChargerOperationInvalidException("Charger " + chargerId + " is not active");
            else if (charger.isOccupied() || charger.getChargingEnergy() > 0.0)
                throw new ChargerInUseException("Charger " + chargerId + " is occupied");

            charger.setActive(false);
        }
    }

    public void activateCharger(String chargerId) {
        Charger charger = getCharger(chargerId);
        if (charger == null)
            throw new ChargerNotFoundException("Could not find charger with id " + chargerId);

        synchronized (getLock(charger.getHubId())) {
            if (charger.isActive())
                throw new ChargerOperationInvalidException("Charger " + chargerId + " is not active");

            charger.setActive(true);
        }
    }
    
    @EventListener(ChargingProgressEvent.class)
    public void updatePowerOutputForCharger(ChargingProgressEvent event) {
        double newPowerKw = event.newPowerKw();
        String hubId = event.hubId();
        String chargerId = event.chargerId();
        synchronized (getLock(hubId)) {
            Charger charger = hubs.get(hubId).get(chargerId);

            log.info("Received ChargingProgressEvent event for charger {} to update to power {}", charger.getChargerId(), newPowerKw);
            charger.setChargingEnergy(newPowerKw);
        }
    }

    @EventListener(ChargingEndedEvent.class)
    public void freeCharger(ChargingEndedEvent event) {
        String hubId = event.hubId();
        String chargerId = event.chargerId();
        Charger charger = hubs.get(hubId).get(chargerId);

        synchronized (getLock(hubId)) {
            log.info("Received ChargingEndedEvent event for charger {}", chargerId);
            charger.setChargingEnergy(0.0);
            charger.setOccupied(false);
        }

        log.info("Publishing ChargerFreedEvent event for hub {}", hubId);
        applicationEventPublisher.publishEvent(new ChargerFreedEvent(charger.getHubId()));
    }

    public Map<String, Map<String, Charger>> getCurrentHubStates() {
        return Collections.unmodifiableMap(hubs);
    }

    /**
     * Interroga l'hub specifico per ottenere la lista di connettori disponibili alla ricarica
     */
    private Set<Charger> filterAvailableChargers(String hubId) {
        Set<Charger> freeChargers = new HashSet<>();
        for (Map.Entry<String, Charger> charger : hubs.getOrDefault(hubId, new HashMap<>()).entrySet()) {
            Charger state = charger.getValue();
            if (state.isActive() && !state.isOccupied())
                freeChargers.add(state);
        }
        return freeChargers;
    }

    /**
     * Restituisce un connettore dato il suo chargerId
     */
    private Charger getCharger(String chargerId) {
        Pattern pattern = Pattern.compile("^(.+?)_col\\d+$");
        Matcher matcher = pattern.matcher(chargerId);

        String hubId = null;
        if (matcher.matches()) {
            hubId = matcher.group(1);
        } else {
            for (Map.Entry<String, Map<String, Charger>> hub : hubs.entrySet()) {
                if (hub.getValue().containsKey(chargerId)) {
                    hubId = hub.getKey();
                    break;
                }
            }
        }

        return hubs.get(hubId).getOrDefault(chargerId, null);
    }

    private Object getLock(String hubId) {
        return hubLocks.computeIfAbsent(hubId, k -> new Object());
    }

}
