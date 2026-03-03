# Keevo

**Keevo** est une application mobile-first (Flutter) + desktop de gestion d'inventaire et de ventes, ciblant les petits commerçants informels et les grossistes multi-boutiques au Cameroun.

> Philosophie : **"Augmenter, ne pas Remplacer"** — Keevo s'insère dans les habitudes existantes (WhatsApp, cahiers) au lieu de forcer les utilisateurs à changer.

---

## Monorepo Structure

```
keevo/
├── app/                    # Flutter (mobile + desktop)
├── backend/                # Spring Boot (Java 21, Maven)
├── .github/
│   └── workflows/
│       ├── backend-ci.yml
│       └── flutter-ci.yml
├── docker-compose.yml      # Local dev (PostgreSQL 16)
├── .gitignore
└── README.md
```

---

## Prerequisites

| Tool | Version |
|---|---|
| Flutter | 3.41+ |
| Java | 21 LTS |
| Maven | 3.9+ |
| Docker + Docker Compose | latest |

---

## Quick Start

### 1. Start local database

```bash
cp backend/.env.example backend/.env
# Edit backend/.env to set POSTGRES_PASSWORD
docker-compose up -d
```

### 2. Run backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 3. Run Flutter app

```bash
cd app
flutter pub get
flutter run
```

---

## Architecture

- **Backend**: Spring Boot 3.5.x, hexagonal architecture (ports & adapters), multi-tenant PostgreSQL via Flyway
- **Frontend**: Flutter 3.41, Riverpod state management, Drift local DB (SQLite), go_router
- **Sync**: Offline-first (7 days), delta-based sync, conflict resolution via last-write-wins

---

## Key Design Principles

1. **Offline-first** — 7 days without connectivity guaranteed
2. **MCP-ready** — Every use case port is pure Java/Dart, no framework coupling
3. **Multi-tenant** — Schema-per-tenant PostgreSQL isolation
4. **TDD** — Red → Green → Refactor on every story

---

## License

Proprietary — © 2026 Keevo. All rights reserved.
