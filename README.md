# Paperclip-Jules Bridge

This repository implements a lightweight, standalone HTTP bridge between [Paperclip](https://paperclip.ai) and the Jules AI service. It is designed to expose a webhook receiver for Paperclip, orchestrate API calls to instantiate tasks in Jules, persist state, and asynchronously poll Jules to report task completions back to Paperclip.

## Overview

When an issue is assigned or manipulated within Paperclip, it can trigger an HTTP webhook. The **Paperclip-Jules Bridge** listens for these webhook invocations. Upon receiving a valid request, the bridge performs the following actions:

1. **Webhook Ingestion**: Exposes a `POST /v1/invocations` endpoint to receive payloads from Paperclip.
2. **Issue Fetching**: Contacts the Paperclip API to fetch detailed information about the referenced issue.
3. **Prompt Generation**: Constructs an actionable prompt string mapping the issue details and predefined invariants, explicitly instructing Jules to create a Pull Request referencing the task ID.
4. **Jules Orchestration**: Asynchronously spawns a new session in the Jules API using the built prompt.
5. **State Persistence**: Persists run data locally using a lightweight SQLite database to ensure resiliency and proper idempotency checks.
6. **Asynchronous Polling**: A background worker continually polls the Jules API for updates on running sessions. Once a session reaches a terminal state (either completion with a PR, or failure), the bridge updates the local database and fires a callback to Paperclip, resolving the task lifecycle.

## Architecture and Technology Stack

The application is built using a modern, reactive-friendly stack designed for simplicity and high performance:

*   **Language**: Kotlin (2.4.10)
*   **Runtime**: Java 25 utilizing Virtual Threads for high-concurrency, blocking I/O resilience.
*   **Framework**: Spring Boot 4.1.0 (Strictly adhering to Functional Configuration patterns with `ApplicationContextInitializer` and `RouterFunction`).
*   **Persistence**: SQLite (single-instance file database) manipulated via Spring `JdbcTemplate`.
*   **Migrations**: Flyway.
*   **Serialization**: `kotlinx.serialization`.

## Requirements

To build and run the bridge locally, you need:
*   Java Development Kit (JDK) 25
*   Gradle (Wrapper is included)

## Configuration

Configuration is managed via the `src/main/resources/application.yaml` file, which is heavily parameterized via environment variables.

You must create a `.env` or `.ENV` file in the root directory to populate these variables locally. The application parses this file automatically on startup.

**Example `.env`:**
```env
BRIDGE_AUTH_TOKEN=your_secure_bearer_token
BRIDGE_ALLOWED_REPOS=Pilleo/mazewall,YourOrg/your-repo
BRIDGE_INVARIANTS_FILE=/path/to/your/invariants.md
JULES_API_BASE_URL=https://jules.googleapis.com/v1alpha
JULES_API_KEY=your_jules_api_key
PAPERCLIP_BASE_URL=https://api.paperclip.ai
PAPERCLIP_API_TOKEN=your_paperclip_api_token
DATABASE_URL=jdbc:sqlite:runs.sqlite
```

## Running the Application

To run the application locally:

```bash
./gradlew bootRun
```

The application will start on `http://localhost:8080` (by default) and auto-migrate the local SQLite database defined in your `DATABASE_URL`.

### Health Endpoints
The bridge provides two built-in health routes:
*   `GET /health/live`: Returns `OK` if the application server is up.
*   `GET /health/ready`: Returns `OK` if the database is reachable and the application is ready to process webhooks.

## Testing

The application enforces a minimum of 80% test coverage and uses OkHttp's `MockWebServer` to mock external interactions securely without touching live services.

Run the test suite via:
```bash
./gradlew check
```

## Contributing

When contributing to this project, adhere to the following principles defined in `AGENTS.md`:
*   Use standard Kotlin formatting.
*   Stick to Spring Boot Functional routing (`router {}`) and context initializers. Avoid `@Configuration`, `@Bean`, or component scanning where possible.
*   Maintain Test-Driven Development strategies.
