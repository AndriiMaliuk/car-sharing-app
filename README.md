# Car Sharing API

A car rental REST API with JWT authentication, Stripe Checkout payments, and Telegram notifications.

## Features

- Registration and login with `CUSTOMER` and `MANAGER` roles.
- Car browsing and fleet management for managers.
- Rental creation, filtering active and completed rentals, and car returns.
- Ownership checks for customer rentals and payments.
- Rental cost calculation and Stripe Checkout link creation.
- Webhook signature, amount, and currency validation before confirming payment.
- Rental creation notifications and scheduled overdue rental checks.
- Database migrations and API documentation in Swagger UI.

## Technology Stack

| Purpose | Technologies |
| --- | --- |
| Language and build | Java 17, Maven, Maven Wrapper |
| Backend | Spring Boot 3.2.5, Spring Web, Bean Validation |
| Authentication and authorization | Spring Security, JWT, JJWT 0.13.0 |
| Data persistence | Spring Data JPA, Hibernate, PostgreSQL, Liquibase |
| Mapping | MapStruct 1.5.5.Final, Lombok |
| Payments | Stripe Java SDK 34.0.0, Checkout, webhooks |
| Notifications | Telegram Bot API, Spring RestClient |
| Documentation | springdoc-openapi, Swagger UI |
| Testing | JUnit 5, Mockito, Spring Boot Test, MySQL Testcontainers |
| Quality checks | Checkstyle, JaCoCo, GitHub Actions |
| Containers | Docker, Docker Compose, Eclipse Temurin 17 |

The current Compose setup uses PostgreSQL, while database-backed tests use MySQL 8.0.36 through Testcontainers and mocks for external services.

## Model Diagram

```mermaid
erDiagram
    USERS ||..o{ RENTALS : rents
    CARS ||..o{ RENTALS : booked_for
    RENTALS ||--o| PAYMENTS : has

    USERS {
        bigint id PK
        varchar email UK
        varchar password
        varchar first_name
        varchar last_name
        varchar role
        boolean is_deleted
    }
    CARS {
        bigint id PK
        varchar brand
        varchar model
        varchar type
        int inventory
        decimal daily_fee
    }
    RENTALS {
        bigint id PK
        date rental_date
        date return_date
        date actual_return_date
        bigint user_id
        bigint car_id
        boolean active
    }
    PAYMENTS {
        bigint id PK
        bigint rental_id FK, UK
        varchar session_id UK
        varchar session_url
        bigint amount_total
        varchar currency
        varchar payment_intent_id
        varchar payment_status
    }
```

Each rental belongs to a user and a car and can have at most one payment. `Rental` stores `userId` and `carId` as scalar IDs; the migrations currently do not define foreign keys for these two fields. `Payment.rental` is a JPA one-to-one relationship backed by a unique foreign key. Payment amounts are stored in minor currency units (cents for USD).

## Requirements

- JDK 17.
- Docker Desktop with Linux containers and Docker Compose.
- Maven or the Maven Wrapper included in the repository.
- A Stripe test environment and Stripe CLI for payment testing.
- A Telegram bot and chat ID for notifications.

Check which Java version Maven uses:

```bash
mvn -version
```

The output should show Java 17. Newer JDKs may be incompatible with the current Lombok version.

Run the commands below from the project root. You can replace `mvn` with `./mvnw` in Git Bash/Linux/macOS or `.\mvnw.cmd` in PowerShell.

## Environment Setup

Copy `.env.template` to `.env` next to `pom.xml` (`cp .env.template .env` in Git Bash, or `Copy-Item .env.template .env` in PowerShell). The template contains these local development settings:

```dotenv
SPRING_APPLICATION_NAME=car-sharing-app
JWT_EXPIRATION=86400000
JWT_SECRET=replace-with-your-own-random-secret-at-least-32-bytes
POSTGRES_USER=postgres
POSTGRES_PASSWORD=replace-with-local-database-password
POSTGRES_DATABASE=carsharing
POSTGRES_LOCAL_PORT=5432
POSTGRES_DOCKER_PORT=5432
SPRING_LOCAL_PORT=8080
SPRING_DOCKER_PORT=8080
DEBUG_PORT=5005
STRIPE_SECRET_KEY=sk_test_replace_me
STRIPE_SUCCESS_URL=http://localhost:8080/swagger-ui/index.html
STRIPE_CANCEL_URL=http://localhost:8080/payments/cancel
STRIPE_WEBHOOK_SECRET=whsec_replace_me
TELEGRAM_BOT_TOKEN=replace-with-bot-token
TELEGRAM_CHAT_ID=replace-with-chat-id
TELEGRAM_API_URL=https://api.telegram.org
RENTAL_OVERDUE_CHECK_CRON=0 0 9 * * *
RENTAL_OVERDUE_CHECK_ZONE=Europe/Kiev
```

Replace the placeholders with your own values. Do not wrap values in quotes: Spring imports this file as `.properties`. The `.env` file is excluded from Git.

- `JWT_EXPIRATION` is measured in milliseconds. `JWT_SECRET` must contain at least 32 UTF-8 bytes.
- PostgreSQL settings create the user and database during initial setup. An existing database requires its original credentials.
- Get `STRIPE_SECRET_KEY` from your Stripe test environment. Connect the CLI to the same environment.
- Get `STRIPE_WEBHOOK_SECRET` from the output of `stripe listen` for your local listener.
- `STRIPE_SUCCESS_URL` is the return page after Checkout. This example uses the existing Swagger UI; there is no separate `/payments/success` endpoint yet.
- `STRIPE_CANCEL_URL` displays a message explaining that payment can be completed later. The session lifetime starts when the session is created.
- Create a Telegram bot token through BotFather. The bot must have access to the specified chat.
- The example schedule runs daily at 09:00 Kyiv time. Set `RENTAL_OVERDUE_CHECK_CRON=-` to disable scheduled checks.

## Running with Docker

The current `compose.yaml` sets `platform: linux/arm64` for PostgreSQL. On Intel/AMD machines, remove this line so Docker can select the host architecture.

Build the JAR first: the Dockerfile copies the artifact from `target` rather than running Maven.

```bash
mvn clean verify
docker compose up --build -d
docker compose logs -f app
```

The application is available at `http://localhost:8080`, and PostgreSQL at `localhost:5432`. Within Compose, the application connects to the database using the hostname `postgresdb`. Port `5005` is used for Java debugging.

Useful commands:

```bash
docker compose ps
docker compose logs --tail=100 app
docker compose stop
```

Recreate the application container after changing `.env`:

```bash
docker compose up -d --force-recreate app
```

After changing Java code, rebuild the JAR and run `docker compose up --build -d` again.

Compose does not yet define an explicit persistent volume or pin the PostgreSQL version. Do not rely on data surviving database removal or recreation; configure storage before using important data. The current `depends_on` setting controls startup order but does not wait for the database to become ready.

## Running the Application Outside Docker

You can keep PostgreSQL in Docker and run the application through your IDE or Maven:

```bash
docker compose stop app
docker compose up -d postgresdb
```

Add the following to your local `.env`:

```dotenv
SPRING_DOCKER_COMPOSE_ENABLED=false
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carsharing
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=replace-with-local-database-password
```

These values must match your database settings. Then run:

```bash
mvn spring-boot:run
```

## API

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)

For protected requests, send the JWT returned by the login endpoint:

```http
Authorization: Bearer <token>
```

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/auth/registration` | Register a customer |
| POST | `/auth/login` | Log in and obtain a JWT |
| GET / PUT | `/users/me` | View and update your profile |
| PUT | `/users/{id}/role` | Update a user role as a manager |
| GET | `/cars`, `/cars/{id}` | Browse cars |
| POST | `/cars` | Add a car as a manager |
| PUT / DELETE | `/cars/{id}` | Update or delete a car as a manager |
| POST | `/rentals` | Create a rental |
| GET | `/rentals?is_active=true` | List active rentals; also supports `user_id`, `page`, `size`, and `sort` |
| GET | `/rentals/{id}` | View rental details |
| POST | `/rentals/{id}/return` | Return a car; no request body |
| POST | `/payments` | Create a payment session |
| GET | `/payments/{id}` | View a payment |
| GET | `/payments/cancel` | Display a cancellation message |
| POST | `/payments/webhook` | Receive Stripe events with signature verification |

Registration:

```json
{
  "email": "user@example.com",
  "password": "Password123",
  "repeatPassword": "Password123",
  "firstName": "John",
  "lastName": "Smith"
}
```

Create a rental:

```json
{
  "carId": 1,
  "daysCount": 2
}
```

Create a payment:

```json
{
  "rentalId": 1
}
```

The server calculates the amount using the planned rental dates and the car's current daily fee, with a minimum charge of one day. The currency is currently fixed to USD. `amountTotal` is stored in cents: `10000` means 100 USD.

## Testing Stripe Payments

Install the [Stripe CLI](https://github.com/stripe/stripe-cli), then run:

```bash
stripe login
stripe listen --events checkout.session.completed,checkout.session.async_payment_succeeded --forward-to localhost:8080/payments/webhook
```

1. Copy the returned `whsec_...` value into `.env` and recreate the application container.
2. Keep the CLI listener running.
3. Log in and create a payment for your rental through `POST /payments`.
4. Open `sessionUrl` and complete the payment in Stripe test mode.
5. Check that the webhook receives a `200` response in the CLI.
6. Fetch the payment through `GET /payments/{id}` and verify `paymentStatus: PAID`.

Create the session through the application: an arbitrary CLI event may refer to a payment that does not exist in the database. Visiting the success page alone does not confirm payment. The webhook verifies the signature, amount, and currency before updating the status.

The current model allows one payment per rental. Repeated creation requests return a conflict. Replacing expired sessions and protecting session creation against concurrent requests are not implemented yet. A repeated notification processed after a payment is already marked as paid leaves its status unchanged.

## Tests and CI

```bash
mvn test
mvn clean verify
mvn checkstyle:check
```

Test settings are in `src/test/resources/application.properties`: MySQL 8.0.36 managed by Testcontainers, disabled `.env` loading and Docker Compose, and placeholder credentials for external services. Start Docker Desktop with Linux containers before running tests. The Testcontainers JDBC driver starts and stops disposable containers automatically; no local MySQL installation or fixed database port is needed. The first run downloads the MySQL image. Service tests use Mockito. Webhook tests verify signed events without calling the Stripe API.

`ApiIntegrationTest` uses the full Spring context, MockMvc, JWT authentication, real repositories, and Liquibase migrations against a separate MySQL test container. It covers registration, access control, rentals and returns, payment creation, Stripe failures, and signed webhooks. Only the outgoing Stripe and notification services are mocked. These tests run with `mvn test` and `mvn verify`; Docker is required, but external credentials are not. To run them separately, use `mvn -Dtest=ApiIntegrationTest test`.

JaCoCo enforces at least **60% line coverage across the entire `org.carsharingapp.service` package**. Controllers, security classes, DTOs, and generated mapper classes are not included in this metric. After `verify`, the report is available at `target/site/jacoco/index.html`.

GitHub Actions runs `mvn --batch-mode --update-snapshots verify` on Java 17 for pushes and pull requests.

## Project Structure

```text
src/main/java/org/carsharingapp/
  config/        Spring, Security, Stripe, and MapStruct configuration
  controller/    REST API
  dto/           Requests and responses
  mapper/        DTO and entity mapping
  model/         JPA entities
  repository/    Database access
  security/      JWT and authentication
  service/       Business logic and integrations
src/main/resources/db/changelog/
                 Liquibase migrations
src/test/        Tests and test configuration
```

## Troubleshooting

- `TypeTag :: UNKNOWN` during compilation: check `mvn -version` and point `JAVA_HOME` to JDK 17.
- `stripe: command not found`: add the Stripe CLI directory to `PATH` and open a new terminal.
- Webhook returns `400`: check the secret from the current CLI listener and restart the application after changing it.
- Event deserialization fails: the webhook API version must be compatible with the Stripe SDK version in use.
- `Payment not found for session`: create the session through the application API and verify that the CLI is connected to the same test environment.
- Port already in use: change the external port in `.env`, and update the return URLs and Stripe CLI forwarding address.
