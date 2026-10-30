# HealthSafe

HealthSafe is a small Java 17 microservices project for ward data, emergency
status, staffing schedules, and equipment-failure alerts. Each service is a
separate Maven application built with Javalin; services communicate over HTTP
and ActiveMQ Classic.

## How it works

```text
wards-outdated.csv
       |
       v
Ingestion Service --HTTP--> Ward Service <--HTTP-- Staffing Service
                                  ^                    |
                                  |                    +--HTTP--> Alert Level Service
                                  |                                (current level)
                                  |
                      staffing-events-topic
                                  ^
                                  |
                         Staffing Service

Ward Service --equipment-failure-queue--> Equipment Alert Service
```

1. **Ingestion** reads `wards-outdated.csv` from its classpath, normalizes ward
   IDs and names, merges rows with the same normalized ID, and reports invalid
   bed counts in `notes`. The Ward Service fetches this list over HTTP.
2. **Ward** serves the list and individual ward records. It fetches the records
   from Ingestion when requested; it does not keep a separate persistent copy.
3. **Alert Level** stores the hospital's current emergency level in memory,
   from `0` (normal) through `8` (full Code Blue).
4. **Staffing** validates a ward with Ward, reads the current emergency level
   from Alert Level, then returns a generated on-call schedule. A `POST`
   schedule request also publishes a staffing event to an ActiveMQ topic.
5. **Equipment alerts** are sent by Ward to an ActiveMQ queue and consumed by
   Equipment Alert, which logs received messages.

The topic broadcasts staffing events to subscribers. The queue distributes
equipment alerts to a consumer. ActiveMQ connection and destination names can
be configured with environment variables (see [Configuration](#configuration)).

## Services and endpoints

All services expose `GET /health`, which returns `OK`.

| Service | Directory | Port | Other endpoints |
|---|---|---:|---|
| Ingestion | [`ingestion-service/`](ingestion-service) | 7030 | `GET /wards` |
| Ward | [`ward-service/`](ward-service) | 7031 | `GET /wards`, `GET /wards/{id}`, `POST /wards/{id}/equipment-failures` |
| Alert Level | [`alert-level-service/`](alert-level-service) | 7032 | `GET /alert-level`, `PUT` or `POST /alert-level` |
| Staffing | [`staffing-service/`](staffing-service) | 7033 | `GET /staffing/{wardId}`, `GET /schedule/{wardId}`, `POST /staffing/{wardId}` |
| Equipment Alert | [`equipment-alert-service/`](equipment-alert-service) | 7034 | Consumes the equipment alert queue |

### Example requests

Build and start all services as described in [Running locally](#running-locally),
then open **Terminal 7** (or reuse Terminal 1 after the broker has started) and
run these requests there. Keep Terminals 2–6 dedicated to their running Java
services; do not enter `curl` commands in those terminals.

```sh
# Inspect the cleaned ward list and a single ward
curl http://localhost:7030/wards
curl http://localhost:7031/wards/W-05

# Read and change the emergency level (valid values: 0 through 8)
curl http://localhost:7032/alert-level
curl -X PUT http://localhost:7032/alert-level \
  -H 'Content-Type: application/json' \
  -d '{"level":4}'

# Get a schedule, or request one and broadcast it to the topic
curl http://localhost:7033/staffing/W-05
curl -X POST http://localhost:7033/staffing/W-05

# Queue an equipment failure alert
curl -X POST http://localhost:7031/wards/W-05/equipment-failures \
  -H 'Content-Type: application/json' \
  -d '{"equipment":"ventilator-2","failure":"Power failure"}'
```

Ward IDs are normalized (for example `w5`, `W05`, and `W-05` resolve to the
same canonical ID). Staffing schedules use `max(1, 1 + floor(level / 2))`
placeholder doctor names. A successful equipment failure request returns
`202 Accepted`; the Equipment Alert Service logs the consumed alert.

### Try the complete workflow

Run these commands in Terminal 7, in order. The first request changes the
in-memory emergency level to `4`; the schedule should then contain three
placeholder doctors. The final two requests publish messages through ActiveMQ.

```sh
# Set the emergency level
curl -X PUT http://localhost:7032/alert-level \
  -H 'Content-Type: application/json' \
  -d '{"level":4}'
# Expected: {"level":4}

# Get a schedule based on level 4
curl http://localhost:7033/staffing/W-05
# Expected: a schedule with alertLevel 4 and three doctors

# Broadcast a staffing update to the topic
curl -X POST http://localhost:7033/staffing/W-05
# Expected: HTTP 202; look for "Received staffing update" in Terminal 3

# Send an equipment failure through the queue
curl -X POST http://localhost:7031/wards/W-05/equipment-failures \
  -H 'Content-Type: application/json' \
  -d '{"equipment":"ventilator-2","failure":"Power failure"}'
# Expected: {"status":"queued"}; look for "Received equipment failure alert"
# in Terminal 6
```

In the observed run, these requests returned `200`, `200`, `202`, and `202`,
respectively, and Equipment Alert logged the equipment failure. Staffing topic
events are broadcast only to active subscribers; a subscriber started after an
event was published will not receive that earlier event. If Terminal 3 does not
show the staffing log, confirm Ward is running and subscribed, then send another
`POST /staffing/W-05`.

## Running locally

### Requirements

- Java 17 or newer
- Maven 3.8 or newer
- Docker Engine and the Docker Compose v2 plugin, for ActiveMQ

Before starting the broker, check that Compose is installed:

```sh
docker compose version
```

If this prints `docker: unknown command: docker compose`, the Compose plugin
is missing. On Ubuntu 24.04, install it with:

```sh
sudo apt update
sudo apt install docker-compose-v2
```

On other systems, install the Compose v2 plugin using the
[Docker Compose installation guide](https://docs.docker.com/compose/install/).
Then rerun `docker compose version`.

Each service directory is an **independent Maven project**; there is no
root-level aggregator. Jackson is declared in modules that return or consume
JSON, so build the modules after dependencies change. Open a terminal at the
project root (`sin-000-healthsafe`) and build all five JARs:

```sh
for service in ingestion-service ward-service alert-level-service staffing-service equipment-alert-service; do
  (cd "$service" && mvn package) || exit 1
done
```

Next, start the broker. In **Terminal 1**, from the project root, run:

```sh
docker compose -f common/docker-compose.yml up -d
```

The broker runs in the background, so you can reuse Terminal 1 after the
command completes. Start each Java service in a separate terminal, from the
project root. Keep each service terminal open while you use the application:

**Terminal 2 — Ingestion Service**
```sh
cd ingestion-service && java -jar target/ingestion-service.jar
```

**Terminal 3 — Ward Service**
```sh
cd ward-service && java -jar target/ward-service.jar
```

**Terminal 4 — Alert Level Service**
```sh
cd alert-level-service && java -jar target/alert-level-service.jar
```

**Terminal 5 — Staffing Service**
```sh
cd staffing-service && java -jar target/staffing-service.jar
```

**Terminal 6 — Equipment Alert Service**
```sh
cd equipment-alert-service && java -jar target/equipment-alert-service.jar
```

Once the services are running, use **Terminal 1** (or open another terminal)
for the `curl` API examples in [Example requests](#example-requests). Do not
enter those requests in a service terminal because the service process is
running there.

The REST requests that cross service boundaries require their dependent
services to be running. For example, Ward needs Ingestion; Staffing needs Ward
and Alert Level. MQ publishing and consumption require the broker. Check service
health with, for example:

```sh
curl http://localhost:7031/health
docker compose -f common/docker-compose.yml ps
```

To stop a service, press `Ctrl+C` in that service's terminal. Stop the broker
when finished by running this in any terminal at the project root:

```sh
docker compose -f common/docker-compose.yml down
```

## Configuration

Defaults assume every service and the broker run on the same machine.

| Environment variable | Used by | Default |
|---|---|---|
| `INGESTION_SERVICE_URL` | Ward Service | `http://localhost:7030` |
| `WARD_SERVICE_URL` | Staffing Service | `http://localhost:7031` |
| `ALERT_LEVEL_SERVICE_URL` | Staffing Service | `http://localhost:7032` |
| `ACTIVEMQ_BROKER_URL` | MQ producer/consumer services | `tcp://localhost:61616` |
| `STAFFING_EVENTS_TOPIC` | Staffing and Ward Services | `staffing-events-topic` |
| `EQUIPMENT_FAILURE_QUEUE` | Ward and Equipment Alert Services | `equipment-failure-queue` |

The broker's web console is available at http://localhost:8161 when the
`common/docker-compose.yml` broker is running. The image's default console
credentials are `admin` / `admin`; use these development defaults only locally.

## Project layout

```text
healthsafe/
├── ingestion-service/       # CSV cleanup and ward REST endpoint
├── ward-service/            # Ward REST API, topic consumer, queue producer
├── alert-level-service/     # In-memory emergency status REST API
├── staffing-service/        # Schedule REST API and topic producer
├── equipment-alert-service/ # Queue consumer for equipment alerts
└── common/                  # ActiveMQ Compose configuration
```

`common/` contains the broker Compose file and documentation, not a shared Java
module. Each service that uses ActiveMQ has its own copy of the small MQ
configuration class because the services build independently.

## Current behavior and limitations

- The alert level and all service state are in memory; restarting a service
  resets its alert level to `0`.
- Staffing uses placeholder doctor names and a simple level-to-count formula,
  not a real clinician roster or scheduling system.
- Staffing events are currently logged by Ward; they do not update a stored
  staffing model.
- Equipment alerts are currently logged by Equipment Alert; they are not saved
  or forwarded to another notification system.
- The ingestion cleaner handles the CSV's ward IDs, whitespace, casing,
  department aliases, duplicate IDs, and bed-count values. It does not parse
  dates or implement every possible data-quality rule described in
  [`ingestion-service/README.md`](ingestion-service/README.md).
- There are no automated test sources in the modules yet. Run `mvn test` from
  an individual service directory after adding tests there; use the `/health`
  endpoints for a basic manual startup check.
- When a JSON endpoint reports that no object mapper is configured, rebuild
  the affected service with `mvn package` and restart its Java process so it
  loads the updated JAR.

## More details

- [Ingestion and source CSV notes](ingestion-service/README.md)
- [Staffing and ActiveMQ topic notes](staffing-service/README.md)
- [Ward API and messaging notes](ward-service/README.md)
- [Alert level service](alert-level-service/README.md)
- [Equipment alert queue](equipment-alert-service/README.md)
- [Broker setup](common/README.md)
  WTC-529W66DN