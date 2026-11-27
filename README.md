# Ride-Hailing Microservices System (Simulating Grab/Uber)

Course: Distributed Systems (DS03)  
Architecture: **Independent Gradle Microservices Projects** + Spring Cloud Gateway + Apache Kafka + PostgreSQL Database-per-Service + Prometheus / Grafana Monitoring.

## Team & Task Assignment

| # | Member | Student ID | Main responsibility | Support |
|---|---|---|---|---|
| 1 | Nguyễn Hải Nam | B23DCCE069 | identity-service (register, login, JWT), driver-service (status, location, CAS reservation, assignment confirmation), security tests | API Gateway, API contracts |
| 2 | Nguyễn Việt Anh | B23DCCE009 | trip-service (trip lifecycle, Saga orchestration, assignment recovery), matching-service (driver search and reservation, rematching), concurrency test scenarios | Integration tests, demo acceptance |
| 3 | Bùi Ngọc Thạch | B23DCDT232 | pricing-service, payment-service, notification-service, Kafka (topics, retry, DLQ) | Docker Compose, monitoring (Prometheus, Grafana), CI/CD, passenger and driver web UI |

The project report is in `docs/`.

---

## 1. Architecture Overview

```
                                      +-------------------------+
                                      |   Client / Web / App    |
                                      +-------------------------+
                                                   |
                                                   v :8080
                                      +-------------------------+
                                      |       API Gateway       |
                                      +-------------------------+
                                                   |
                         +-------------------------+-------------------------+
                         | (Docker DNS / HTTP)                               |
       +-----------------+-----------------+               +-----------------+-----------------+
       |                 |                 |               |                 |                 |
       v :8081           v :8082           v :8083         v :8084           v :8085           v :8086
+--------------+  +--------------+  +--------------+  +--------------+  +--------------+  +--------------+
|   Identity   |  |Driver/Location| | Trip Service |  |   Matching   |  |   Pricing    |  |   Payment    |
+--------------+  +--------------+  +--------------+  +--------------+  +--------------+  +--------------+
       |                 |                 |                 |                                 |
       v                 v                 v                 v                                 v
[ identity_db ]   [  driver_db  ]   [   trip_db   ]   [  matching_db ]                 [  payment_db  ]
                                           |                 |                                 |
                                           +--------+--------+---------------------------------+
                                                    | (Events via Kafka:9092)
                                                    v
                                      +-------------------------+
                                      |   Apache Kafka (KRaft)  |
                                      +-------------------------+
                                                    |
                                                    v
                                      +-------------------------+
                                      |  Notification Service   | (:8087)
                                      +-------------------------+
```

---

## 2. Directory Structure (Independent Gradle Projects)

> **CRITICAL ARCHITECTURAL PRINCIPLE:**  
> The root folder is **NOT** a Gradle project. There is **NO** root `build.gradle`, **NO** root `settings.gradle`, and **NO** root `gradlew`.  
> Every microservice is a completely independent Gradle project with its own wrapper, build file, and Docker build context.

```
ride-hailing-microservices/
├── docker-compose.yml
├── .env
├── .env.example
├── .gitignore
├── README.md
├── docs/                       # Project report
├── scripts/                    # Start/stop, e2e, fault injection, load test, Kafka tools, DB migrations
├── tests/                      # Web UI tests, contract checks, shared test data (tests/data)
├── deploy/
│   └── monitoring/
│       ├── prometheus/
│       │   └── prometheus.yml
│       └── grafana/
│           └── provisioning/
│               └── datasources/
│                   └── prometheus.yml
│
└── src/
    ├── api-gateway/            # Independent Gradle Project (:8080)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   ├── settings.gradle
    │   ├── gradlew / gradlew.bat
    │   └── src/
    │
    ├── identity-service/       # Independent Gradle Project (:8081)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    ├── driver-service/         # Independent Gradle Project (:8082)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    ├── trip-service/           # Independent Gradle Project (:8083)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    ├── matching-service/       # Independent Gradle Project (:8084)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    ├── pricing-service/        # Independent Gradle Project (:8085)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    ├── payment-service/        # Independent Gradle Project (:8086)
    │   ├── Dockerfile
    │   ├── build.gradle
    │   └── src/
    │
    └── notification-service/   # Independent Gradle Project (:8087)
        ├── Dockerfile
        ├── build.gradle
        └── src/
```

---

## 3. Port & Service Mapping

| Service Name | Port (Docker internal) | Port (Host publish) | Database / Persistence | Message Broker |
|---|---|---|---|---|
| **api-gateway** | `8080` | `8080` | None | None |
| **identity-service** | `8081` | Internal only | PostgreSQL (`identity_db`) | None |
| **driver-service** | `8082` | Internal only | PostgreSQL (`driver_db`) | Apache Kafka |
| **trip-service** | `8083` | Internal only | PostgreSQL (`trip_db`) | Apache Kafka |
| **matching-service** | `8084` | Internal only | PostgreSQL (`matching_db`) | Apache Kafka |
| **pricing-service** | `8085` | Internal only | None | None |
| **payment-service** | `8086` | Internal only | PostgreSQL (`payment_db`) | Apache Kafka |
| **notification-service** | `8087` | Internal only | None | Apache Kafka |
| **Kafka (KRaft)** | `9092` | Internal only | Volume (`kafka_data`) | Self |
| **Prometheus** | `9090` | `9090` | Volume (`prometheus_data`) | None |
| **Grafana** | `3000` | `3000` | Volume (`grafana_data`) | None |

---

## 4. How to Build & Run

### Requirements

- Docker with Docker Compose v2 (the whole stack runs in containers)
- JDK 17, only to build or test a service outside Docker
- Node.js 18 or newer, only to run the web UI tests in `tests/`
- Bash (Git Bash on Windows) for the scripts in `scripts/`

### Step 1: Build services individually (Independent Gradle)

```bash
cd src/api-gateway && ./gradlew clean build && cd ../..
cd src/identity-service && ./gradlew clean build && cd ../..
cd src/driver-service && ./gradlew clean build && cd ../..
cd src/trip-service && ./gradlew clean build && cd ../..
cd src/matching-service && ./gradlew clean build && cd ../..
cd src/pricing-service && ./gradlew clean build && cd ../..
cd src/payment-service && ./gradlew clean build && cd ../..
cd src/notification-service && ./gradlew clean build && cd ../..
```

### Step 2: Configure Environment Variables

```bash
cp .env.example .env
```

### Step 3: Run with Docker Compose

```bash
docker compose config
docker compose up -d --build
```

### Step 4: Verify System Health

- **API Gateway Health**: `http://localhost:8080/actuator/health`
- **Prometheus Dashboard**: `http://localhost:9090/targets`
- **Grafana Dashboard**: `http://localhost:3000` (User: `admin` / Password: `admin_secure_password_123`)
- **Kafka UI** (read only): `http://localhost:8090`

Centralized container logs are available in Grafana's **DS03 Logs** dashboard and **Explore → Loki**. Existing Grafana volumes retain
their configured login; the credentials above are only the initial defaults.

### Redeployment

From a fresh clone:

```bash
cp .env.example .env
bash scripts/start.sh          # builds, starts every container and waits until all are healthy
```

After pulling new code, rebuild only the changed services, then restart the stack:

```bash
docker compose build <service> [<service> ...]
docker compose up -d
```

If you keep databases from an older version, first apply the matching scripts in `scripts/db/` (each is safe to rerun). For example:

```bash
docker compose up -d trip-db
docker exec -i trip-db sh -c 'psql -U $POSTGRES_USER -d trip_db' < scripts/db/2026-11-27-drop-payment-provider.sql
```

To start over with empty databases and Kafka topics, run `docker compose down -v` and then `bash scripts/start.sh`.
`bash scripts/stop.sh` stops the stack and keeps the data.

## 5. Web Demo

After starting the stack, open `http://localhost:8080/demo/login.html` for the passenger and driver apps (map, booking, popups), and `http://localhost:8080/demo/index.html` for the two-passengers-one-driver concurrency test. The pages are served by the Gateway and call the real APIs. Service stop/start stays in Docker Desktop or Compose commands.

Demo accounts are created at startup: `driver01` to `driver10@ds03.test` and `passenger01` to `passenger03@ds03.test`, all with password `Demo@12345`. The first eight drivers are available around Hoan Kiem, Hanoi.

### Demonstration scenarios

| Scenario | How to show it |
|---|---|
| Services start independently | Start containers one by one; watch them turn healthy and the health panel in Grafana |
| Driver becomes available | Driver logs in and sets a location; the passenger page shows the car on the map |
| Passenger books a trip | Pick pickup and destination, see the fare and route, press book |
| Matching picks a driver | Status moves to waiting for driver confirmation, then driver on the way |
| Driver accepts and completes | Accept, start, complete; the passenger pays from the wallet and the trip becomes paid |
| Two passengers, one driver | `/demo/index.html`, send A and B at the same time; only one gets the driver |
| A service stops mid-flow | Stop `payment-service` while a trip is running, then complete the trip |
| Retry, queue or compensation | Kafka UI shows the payment group with 0 members and lag 1; or the passenger cancels a trip that already has a driver |
| Restart and continue | Start `payment-service` again; the pay button appears and the lag returns to 0 |

### Tests

```bash
cd src/<service> && ./gradlew test        # unit and integration tests of one service
node --test tests/*test.mjs               # web UI logic
bash tests/e2e-concurrency-test.sh        # contract check of the concurrency script
bash scripts/e2e-happy-path.sh            # full trip through the Gateway (stack running)
bash scripts/e2e-concurrency.sh           # two passengers race for one driver (stack running)
bash scripts/fault-tests.sh               # payment failure and recovery scenarios (stack running)
```
