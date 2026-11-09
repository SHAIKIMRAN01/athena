# Athena frontend

React 19 + Vite. See the main [README](../README.md) and
[important/ARCHITECTURE.md](../important/ARCHITECTURE.md#5-frontend-structure).

```bat
npm install
npm run dev        & rem http://localhost:5173, forwards /api to http://localhost:8080
npm test           & rem unit tests (Vitest)
npm run lint
npm run build      & rem production build in dist\
npx playwright test & rem end-to-end tests, see important/testing/TESTING.md
```
