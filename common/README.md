# common — Asynchronous Decoupling (MQ)

## Overview

Topic: `staffing-events-topic`

Staffing updates are broadcast as Events via the broker to decouple the frontend from the Staffing Service.

Part of the [HealthSafe](../README.md) project. Holds the ActiveMQ broker shared
by the services below — not a service itself, so it has no port of its own.

- Producer: `staffing-service` (`../staffing-service`)
- Consumer(s): ward-service

Broker URL and topic name are shared via a common `co.wethinkcode.healthsafe.mq.MqConfig` class
(`BROKER_URL`, `TOPIC`). It's identical in every participating service's own source
tree — each service here is an independent Maven project with no shared parent pom,
so the common package is duplicated rather than imported from one place.

## Project structure

```
common/
├── docker-compose.yml
└── README.md
```

This folder holds the broker config and notes only — the actual publish/subscribe
code belongs in the producer/consumer services listed above (their poms already
depend on `activemq-client`, and each already has
`src/main/java/co/wethinkcode/healthsafe/mq/MqConfig.java`).

## Build

Nothing to build here directly — this folder just brings up the broker used by the
services listed above.

## Run

```
docker compose up -d
```

- Broker URL for clients: `tcp://localhost:61616`
- Web console: http://localhost:8161 (default admin/admin)

Then start the producer/consumer services as usual (`mvn package && java -jar ...`
from their own directories at the project root).

## Test

```
docker compose ps          # confirm the broker container is healthy
```

Then compute a schedule (`GET http://localhost:7033/schedule/{wardId}`) and
confirm the consumer receives it — via the `staffing-service` and
`ward-service` logs, `GET http://localhost:7031/staffing-events`, or the
web console.

## Status

Both wiring steps are done:

- `staffing-service` publishes to the topic on every schedule computation
  (`co.wethinkcode.healthsafe.mq.TopicPublisher`, reached from
  `GET /schedule/{wardId}`).
- `ward-service` subscribes and stores what it receives
  (`co.wethinkcode.healthsafe.mq.MqManager`), readable via
  `GET /staffing-events` — no synchronous calls back to `staffing-service`.
