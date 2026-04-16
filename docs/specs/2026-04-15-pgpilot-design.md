# PgPilot -- Design Spec

**Date:** 2026-04-15
**Status:** Draft
**Author:** Juano Morello

---

## 1. Overview

PgPilot is a managed workflow orchestration platform where PostgreSQL is the only infrastructure dependency. Teams define workflows as code via a TypeScript SDK, and PgPilot executes them using Postgres-native primitives: SKIP LOCKED for job claiming, advisory locks for coordination, and LISTEN/NOTIFY for real-time signaling.

**One-liner:** "Durable workflows on your Postgres. No new infrastructure."

## 2. Problem

Small engineering teams at funded startups (2-10 devs) need reliable async workflows: onboarding pipelines, payment processing, data sync, report generation. Their options today:

- **Temporal:** Powerful but complex. Requires its own cluster (Cassandra/MySQL + gRPC), dedicated workers, steep learning curve.
- **Inngest:** Simpler, but data leaves your infra. Opaque execution. Vendor lock-in.
- **BullMQ/Sidekiq:** Simple job queues, not workflow engines. No step durability, no fan-in, no event-driven resume.
- **DIY:** Queue table + cron. Works until it doesn't. No observability, no replay, no retries with backoff.

All of these either add infrastructure complexity or move data off the customer's stack.

## 3. Solution

PgPilot connects to the customer's existing PostgreSQL, creates a dedicated `pgpilot` schema, and runs durable workflows entirely within that database. No Redis, no Kafka, no external broker. If your Postgres is up, your workflows run.

## 4. Target Customer

Small engineering teams (2-10 devs) at funded startups that:
- Already run PostgreSQL as their primary database
- Need async workflows beyond simple job queues
- Don't want to operate Temporal or add infrastructure
- Care about data residency (data stays in their Postgres)

## 5. Competitive Positioning

| | Temporal | Inngest | BullMQ | **PgPilot** |
|---|---------|---------|--------|------------|
| Infrastructure | Own cluster | Their cloud | Redis | **Your Postgres** |
| Complexity | High | Medium | Low | **Medium** |
| Workflows/DAGs | Yes | Yes | No | **Yes** |
| Step durability | Yes | Yes | No | **Yes** |
| Self-hostable | Yes (complex) | No | Yes | **Yes (single binary)** |
| Data leaves your infra | No | Yes | No | **No** |
| Webhook ingestion | No | Yes | No | **Yes** |

## 6. Architecture

### 6.1 Deployment Modes

**PgPilot Cloud (primary):** Customer connects their Postgres via connection string. PgPilot creates a `pgpilot` schema and runs orchestrator workers that poll and execute.

**PgPilot Self-Hosted (future):** Single binary that runs alongside the customer's app. Same schema, same SDK. Free tier / open-source play.

### 6.2 Components

| Component | Purpose | Technology |
|-----------|---------|-----------|
| **SDK** | Define workflows, enqueue jobs, register webhooks | TypeScript (primary), REST API (language-agnostic) |
| **Schema** | Queue tables, workflow state, step results, dead-letter, audit log | PostgreSQL (SKIP LOCKED, advisory locks, LISTEN/NOTIFY) |
| **Orchestrator** | Poll for work, claim jobs, execute steps, handle retries/fan-in | Kotlin, Spring Boot, Java 21 virtual threads |
| **Webhook Gateway** | Receive external events, validate signatures, route to workflows | NestJS (part of API Gateway, handles HTTP ingestion and writes events to customer's Postgres) |
| **Dashboard** | Observe workflows, inspect runs, replay failed steps | Next.js, React |
| **API Gateway** | Auth, connection management, tenant isolation, rate limiting | NestJS |

### 6.3 Data Flow

**Internal workflow trigger:**
```
Customer App -> SDK (enqueue) -> Customer's Postgres (pgpilot schema)
                                         ^
                              PgPilot Orchestrator (poll + execute)
                                         ^
                              PgPilot Dashboard (observe)
```

**External webhook trigger:**
```
External Service (Stripe, GitHub, etc.)
        | POST webhook
        v
PgPilot Webhook Gateway (your-team.pgpilot.dev/wh/stripe)
        | validate signature + write event
        v
Customer's Postgres (pgpilot.events table)
        | match against waiting steps OR trigger new workflow
        v
PgPilot Orchestrator (resume/start workflow)
```

### 6.4 Connection Model

1. Customer provides a connection string
2. PgPilot creates the `pgpilot` schema (isolated from customer data)
3. Runs Flyway migrations
4. Uses a dedicated Postgres role with access only to `pgpilot` schema
5. All workflow state lives in the customer's database
6. Churn cleanup: `DROP SCHEMA pgpilot CASCADE`

### 6.5 Multi-tenancy

PgPilot Cloud runs on a central Postgres for auth, billing, and customer metadata. Each customer's workflow data lives in their own database. "Bring your own Postgres" multi-tenancy.

## 7. SDK Design

### 7.1 Workflow Definition

```typescript
import { workflow, step } from '@pgpilot/sdk';

const onboardUser = workflow('user.onboard', async (ctx) => {
  const account = await step.run('create-account', async () => {
    return await db.accounts.create({ email: ctx.input.email });
  });

  await step.run('send-welcome', async () => {
    await emailService.send(account.email, 'welcome');
  });

  const subscription = await step.waitForEvent('stripe.checkout.completed', {
    match: { 'data.customer_email': ctx.input.email },
    timeout: '7d',
  });

  await step.run('provision', async () => {
    await provision(account.id, subscription.plan);
  });

  return { accountId: account.id, plan: subscription.plan };
});
```

### 7.2 SDK Primitives

| Primitive | What it does |
|-----------|-------------|
| `step.run(name, fn)` | Execute a step. Retried on failure. Result is durable (memoized). |
| `step.waitForEvent(event, opts)` | Pause workflow until an external event arrives. Timeout support. |
| `step.sleep(duration)` | Pause workflow for a duration. Uses PG scheduling, no polling. |
| `step.fan(name, items, fn)` | Fan-out: run fn for each item in parallel. Fan-in: wait for all. |
| `step.invoke(workflow, input)` | Trigger another workflow and optionally await its result. |

### 7.3 Step Durability

Each step result is written to Postgres before proceeding to the next step. If the orchestrator crashes mid-workflow, it resumes from the last completed step. No re-processing, no duplicated side effects from earlier steps.

### 7.4 Webhook Registration

```typescript
import { webhook } from '@pgpilot/sdk';

webhook.on('stripe', {
  events: ['checkout.session.completed', 'invoice.paid'],
});

webhook.on('github', {
  events: ['push', 'pull_request.opened'],
});
```

PgPilot generates webhook URLs per provider (`your-team.pgpilot.dev/wh/stripe`), handles signature verification for known providers, and routes events to matching workflows.

### 7.5 Triggering Workflows

```typescript
import { pgpilot } from '@pgpilot/sdk';

const run = await pgpilot.trigger('user.onboard', {
  email: 'user@example.com',
});

const status = await pgpilot.getRun(run.id);
await pgpilot.cancel(run.id);
```

## 8. Data Model

All tables in the `pgpilot` schema within the customer's Postgres.

### 8.1 Core Tables

| Table | Purpose | Key Design |
|-------|---------|-----------|
| `workflows` | Registered workflow definitions | Immutable after creation. Versioned. |
| `runs` | Individual workflow executions | State machine: pending -> running -> completed/failed/cancelled |
| `steps` | Each step within a run | Durable result storage. Status + input/output + error + attempt count |
| `events` | Incoming webhook events and internal signals | Append-only. Matched against waitForEvent subscriptions |
| `dead_letters` | Failed runs/steps that exhausted retries | Replayable. Links back to originating run + step |
| `audit_log` | All mutations | Append-only. Immutable. |

### 8.2 Job Claiming (SKIP LOCKED)

```sql
SELECT id, workflow_id, payload
FROM pgpilot.runs
WHERE status = 'pending'
  AND scheduled_at <= now()
ORDER BY scheduled_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

Multiple orchestrator workers poll safely. No double-processing. No external broker.

### 8.3 Event Matching (waitForEvent)

```sql
SELECT s.id, s.run_id
FROM pgpilot.steps s
WHERE s.status = 'waiting'
  AND s.wait_event_type = 'stripe.checkout.completed'
  AND s.wait_match @> '{"data.customer_email": "user@example.com"}'::jsonb;
```

JSONB containment queries for flexible event matching.

### 8.4 Sleep Without Polling

`step.sleep('2h')` writes a `scheduled_at` timestamp. The orchestrator skips it until that time. LISTEN/NOTIFY signals the orchestrator for near-real-time wake-ups.

### 8.5 Fan-In Coordination

Fan-out creates N child steps. A trigger on `steps` checks if all siblings in the fan group are completed. Advisory locks prevent race conditions during the fan-in check.

### 8.6 Migrations

Flyway manages the `pgpilot` schema. Migrations are backward-compatible. Tested against PostgreSQL 14, 15, 16, and 17.

## 9. Dashboard

### 9.1 Views

| View | Purpose |
|------|---------|
| **Workflows** | List registered workflows, execution stats (runs/day, success rate, p50/p95) |
| **Runs** | Live feed of runs. Filter by workflow, status, date. Click into detail. |
| **Run Detail** | Step-by-step timeline. Status, duration, input/output, retry count, errors. Visual DAG for fan-out. |
| **Dead Letter** | Failed runs that exhausted retries. Inspect, replay from failed step, or discard. |
| **Webhooks** | Registered endpoints, recent deliveries, signature status, replay. |
| **Settings** | Connection health, schema version, API keys, team members, billing. |

### 9.2 Replay from Failed Step

When a workflow fails at step 4 of 6: inspect error in dashboard, fix the code, click "Replay from step 4." Re-executes from that step using durable results from steps 1-3.

### 9.3 Connection Health

Dashboard shows: connection status, latency, schema version, migration status. Alerts if connection drops or `pgpilot` schema is modified externally.

## 10. Pricing

| Plan | Price | Runs/month | Databases | History | Support |
|------|-------|-----------|-----------|---------|---------|
| **Free** | $0 | 5K | 1 | 3 days | Community |
| **Pro** | $49 | 50K | 3 | 30 days | Email |
| **Scale** | $199 | 500K | Unlimited | 90 days | Priority + SLA |
| **Enterprise** | Custom | Unlimited | Unlimited | Custom | Dedicated |

**Overage:** $0.001 per run beyond plan limit (soft limit, notification, not a wall).

**What counts as a "run":** One workflow execution regardless of step count.

## 11. Go-to-Market

### Phase 1: Build in Public (Weeks 1-6)
- Public repo under SurubiLabs
- Blog posts on juanomorello.dev: SKIP LOCKED deep dive, advisory locks, LISTEN/NOTIFY vs polling
- Progress updates on X/Twitter

### Phase 2: Closed Beta (Weeks 6-8)
- 10-20 teams. Free access for feedback + testimonials.
- Fix rough edges.

### Phase 3: Public Launch (Weeks 8-10)
- Show HN, Product Hunt, r/selfhosted, dev.to
- Landing page at pgpilot.dev
- Angle: "Postgres-only, no new infrastructure"

### Ongoing Content
- Blog: PostgreSQL internals (2x/month)
- Changelog: every release
- Comparison pages: Temporal vs PgPilot, Inngest vs PgPilot
- Case studies as available

## 12. Technical Foundation

PgPilot builds on existing proven code:

| Existing Project | What PgPilot Reuses |
|-----------------|-------------------|
| **Konduit** | SKIP LOCKED queues, virtual thread workers, fan-in coordination, Flyway migrations |
| **ForgeStack** | Monorepo structure, NestJS API gateway, Next.js dashboard, deployment templates |
| **nest-tenant** | Multi-tenancy patterns (schema isolation, connection management) |
| **FlagShip** | BullMQ patterns, audit logging, RBAC, SDK design, testing approach |

## 13. Success Criteria

| Milestone | Target |
|-----------|--------|
| MVP (SDK + orchestrator + dashboard) | 8 weeks |
| Closed beta with 10 teams | Week 8 |
| Public launch | Week 10 |
| 50 free-tier users | Month 3 |
| 20 paying customers | Month 6 |
| $10K MRR | Month 12 |

## 14. Non-Goals (v1)

- Python/Go/Java SDKs (TypeScript only for v1)
- Self-hosted binary (cloud-first for v1)
- Visual workflow builder (code-first only)
- Multi-region orchestration
- Workflow versioning/migration tooling
- Built-in alerting (use existing monitoring)
