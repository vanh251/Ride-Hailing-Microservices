# Ride-Hailing Microservices System (Simulating Grab/Uber)

Course: Distributed Systems (DS03)  
Architecture: **Independent Gradle Microservices Projects** + Spring Cloud Gateway + Apache Kafka + PostgreSQL Database-per-Service + Prometheus / Grafana Monitoring.

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
├── docs/
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

## 5. Web Demo

After starting the upgraded stack, open `http://localhost:8080/demo/login.html` for the passenger and driver apps (map, booking, popups), and `http://localhost:8080/demo/index.html` for the two-passengers-one-driver concurrency test. The pages are served by the Gateway and call the real APIs. Service stop/start stays in Docker Desktop or Compose commands.

See [the demo guide](docs/demo-web.md) for setup, fault recovery, session handling, and tests. Existing W3 databases require the cutover/migrations described in `docs/operations.md` before running the W5 application.
