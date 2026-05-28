# Sage IT Co

Career development and IT consulting platform.

## Structure

```
/              → Next.js frontend (deploys to Vercel)
/backend       → Spring Boot backend (deploys to Railway)
```

## Local Development

### Frontend

```bash
npm install
npm run dev
```

Runs on http://localhost:3000.

### Backend

```bash
cd backend
cp .env.example .env   # fill in values
./mvnw spring-boot:run
```

Runs on http://localhost:8080.

## Brand Configuration

All brand-specific values (name, colors, emails, letterhead) are configured via env vars. See:

- Frontend: [src/lib/constants.ts](src/lib/constants.ts) and [tailwind.config.ts](tailwind.config.ts)
- Backend: [backend/src/main/resources/application.properties](backend/src/main/resources/application.properties)
- Env template: [backend/.env.example](backend/.env.example)

## Deployment

- **Frontend**: auto-deploys to Vercel on push to `main`
- **Backend**: deployed to Railway, requires manual setup
