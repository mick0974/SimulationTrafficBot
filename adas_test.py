import requests
import time
import random
import logging
from threading import Thread
import os
from datetime import datetime

LOG_DIR = "./logs/adas_test"
os.makedirs(LOG_DIR, exist_ok=True)

run_ts = datetime.now().strftime("%Y%m%d_%H%M%S")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(threadName)s] %(levelname)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler(f"{LOG_DIR}/adas_{run_ts}.log", encoding="utf-8")
    ]
)

log = logging.getLogger(__name__)

BASE_URL = "http://localhost:8070/api"

'''
HUBS = [
    {"hub_name": "Berlin_Alt_Tempelhof"},
    {"hub_name": "Berlin_Danzinger"},
    {"hub_name": "Berlin_Friedhof"},
    {"hub_name": "Berlin_Hansaplatz"},
    {"hub_name": "Berlin_Heinrich_Heine"},
    {"hub_name": "Berlin_Hospital"},
    {"hub_name": "Berlin_Humboldthain"},
    {"hub_name": "Berlin_Innenstadt"},
    {"hub_name": "Berlin_Linden"},
    {"hub_name": "Berlin_Memhardstr"},
    {"hub_name": "Berlin_Neukölln"},
    {"hub_name": "Berlin_Potsdamer_Platz"},
    {"hub_name": "Berlin_Prenzlauer_Berg"},
    {"hub_name": "Berlin_Rehberge"},
    {"hub_name": "Berlin_Remise"},
    {"hub_name": "Berlin_Rosenthaler"},
    {"hub_name": "Berlin_Savignyplatz"},
    {"hub_name": "Berlin_Schöneberg"},
    {"hub_name": "Berlin_Sophienkirchhof"},
    {"hub_name": "Berlin_Spichernstr"},
    {"hub_name": "Berlin_Tempelhof"},
    {"hub_name": "Berlin_Wolff_Park"},
    {"hub_name": "Berlin_hamburger"},
]
'''

HUBS = [
    {"hub_name": "Berlin_Alt_Tempelhof"},
    {"hub_name": "Berlin_Danzinger"},
]

CHARGER_TYPES = ["CCS", "TYPE2", "CHAdeMO"]


def simulate_request():
    hub = random.choice(HUBS)["hub_name"]
    log.info(f"[{hub}] Avvio simulazione richiesta")

    # =========================
    # 1. Richiesta assegnamento connettore
    # =========================
    payload = {
        "chargerType": random.choice(CHARGER_TYPES),
        "maxVehiclePowerKw": round(random.uniform(7, 150), 2)
    }

    log.info(f"[{hub}] POST assign-charger | payload={payload}")

    try:
        r = requests.post(f"{BASE_URL}/adas/hub/{hub}/assign-charger", json=payload)
        log.info(f"[{hub}] assign-charger → HTTP {r.status_code} | body={r.text}")
    except requests.RequestException as e:
        log.error(f"[{hub}] assign-charger fallita | errore={e}")
        return

    if r.status_code != 202:
        log.warning(f"[{hub}] assign-charger: status inatteso {r.status_code}, skip richiesta")
        return

    request_id = r.json()["requestId"]
    log.info(f"[{hub}] Richiesta accettata | requestId={request_id}")

    # =========================
    # 2. Polling connettore
    # =========================
    assigned = None
    poll_count = 0

    while assigned is None:
        wait = random.uniform(10, 180)
        log.debug(f"[{hub}] Attesa {wait:.1f}s prima del prossimo poll (tentativo #{poll_count + 1})")
        time.sleep(wait)

        poll_count += 1
        log.info(f"[{hub}] GET check-assignment/{request_id} (tentativo #{poll_count})")

        try:
            r = requests.get(f"{BASE_URL}/adas/hub/check-assignment/{request_id}")
            log.info(f"[{hub}] check-assignment → HTTP {r.status_code} | body={r.text}")
        except requests.RequestException as e:
            log.error(f"[{hub}] check-assignment fallita | errore={e}")
            continue

        if r.status_code == 200:
            assigned = r.json()

    charger_id = assigned['chargerId']
    log.info(f"[{hub}] Connettore assegnato | chargerId={charger_id} dopo {poll_count} poll")

    # =========================
    # 3. Simulazione erogazione energia
    # =========================
    steps = random.randint(3, 6)
    log.info(f"[{hub}] Inizio erogazione energia | requestId={request_id} | steps={steps}")

    for step in range(1, steps + 1):
        power = round(random.uniform(5, payload["maxVehiclePowerKw"]), 2)
        log.info(f"[{hub}] POST charge-vehicle | step={step}/{steps} | powerKw={power}")

        try:
            r = requests.post(
                f"{BASE_URL}/adas/hub/charge-vehicle/{request_id}",
                json={"powerKw": power}
            )
            log.info(f"[{hub}] charge-vehicle → HTTP {r.status_code} | body={r.text}")
        except requests.RequestException as e:
            log.error(f"[{hub}] charge-vehicle fallita al step {step} | errore={e}")

        wait = random.uniform(3 * 60, 10 * 60)
        log.debug(f"[{hub}] Attesa {wait:.1f}s prima del prossimo step di ricarica")
        time.sleep(wait)

    # =========================
    # 4. Ricarica terminata
    # =========================
    log.info(f"[{hub}] POST free-charger | requestId={request_id}")

    try:
        r = requests.post(f"{BASE_URL}/adas/hub/free-charger/{request_id}")
        log.info(f"[{hub}] free-charger → HTTP {r.status_code} | body={r.text}")
    except requests.RequestException as e:
        log.error(f"[{hub}] free-charger fallita | errore={e}")

    log.info(f"[{hub}] Simulazione completata | requestId={request_id}")


# =========================
# SCHEDULAZIONE (1 ORA)
# =========================

def main():
    log.info("Avvio simulazione con 15 thread distribuiti in 1 ora")
    threads = []

    for i in range(15):
        delay = 0 #random.uniform(0, 3600)
        log.info(f"Thread {i + 1}/15 schedulato con delay={delay:.1f}s")

        def delayed_run(d, idx):
            log.info(f"Thread {idx} avviato, attesa {d:.1f}s prima della simulazione")
            time.sleep(d)
            simulate_request()

        t = Thread(target=delayed_run, args=(delay, i + 1), name=f"SimThread-{i + 1}")
        t.start()
        threads.append(t)

    log.info("Tutti i thread avviati, in attesa del completamento...")

    for t in threads:
        t.join()

    log.info("Simulazione completata")


if __name__ == "__main__":
    main()