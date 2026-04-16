# PgPilot

> Durable workflows on your Postgres. No new infrastructure.

PgPilot is a managed workflow orchestration platform where PostgreSQL is the only infrastructure dependency. Define workflows as code with the TypeScript SDK; PgPilot runs them on your existing Postgres using `SKIP LOCKED`, advisory locks, and `LISTEN/NOTIFY`.

## Why

Small engineering teams need reliable async workflows (onboarding, payments, data sync, reports) without adopting Temporal's complexity, sending data to Inngest's cloud, or reinventing retries on top of a job queue.

PgPilot runs inside the customer's own Postgres. If your database is up, your workflows run.

## Status

Design spec complete. Implementation plan approved. MVP targeted in 8 weeks.

- Spec: [`docs/specs/2026-04-15-pgpilot-design.md`](docs/specs/2026-04-15-pgpilot-design.md)

## Architecture (at a glance)

| Component             | Stack                                                      |
| --------------------- | ---------------------------------------------------------- |
| SDK (`@pgpilot/sdk`)  | TypeScript, tsup, native `fetch`                           |
| Orchestrator          | Kotlin 2.x, Spring Boot 3, Java 21 virtual threads, Flyway |
| API + Webhook Gateway | NestJS 11, Drizzle, Clerk                                  |
| Dashboard             | Next.js 16, React 19, SCSS Modules, Radix, Motion          |
| Only infra dep        | PostgreSQL 14+                                             |

## Repo

```
apps/        # api, dashboard, storybook, docs
packages/    # sdk, protocol, db, ui, tokens, email, shared, configs
services/    # orchestrator (Kotlin)
docker/      # docker-compose (Postgres 17 only)
docs/        # specs + ADRs
```

## License

MIT — see [LICENSE](LICENSE).

Built by [Juano Morello](https://juanomorello.dev) under [SurubiLabs](https://github.com/SurubiLabs).
