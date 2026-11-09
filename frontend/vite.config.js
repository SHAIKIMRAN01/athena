import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { defineConfig, loadEnv } from 'vite'
import { checkApiBaseUrl } from './src/api/baseUrl.js'
import { contentSecurityPolicy } from './src/lib/csp.js'

// Development: the app runs on http://localhost:5173 and forwards /api to the
// Spring Boot backend, so the browser sees one origin (no CORS).
// Production, two ways:
//   - served by Spring Boot itself (Dockerfile): the API is on the same origin;
//   - hosted on Vercel: VITE_API_BASE_URL names the API (for example on Render),
//     and the backend's CORS_ALLOWED_ORIGINS must list the Vercel address.
const apiTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080'

const SUPPORTED_BROWSERS = ['es2020', 'chrome100', 'edge100', 'firefox100', 'safari15', 'ios15']

// The security headers Vercel sends (vercel.json), also sent by `npm run preview`,
// so a local preview behaves like the deployed site.
const vercelHeaders = Object.fromEntries(
  JSON.parse(readFileSync(new URL('./vercel.json', import.meta.url), 'utf8'))
    .headers.filter((rule) => rule.source === '/(.*)')
    .flatMap((rule) => rule.headers.map(({ key, value }) => [key, value])),
)

/** Writes the Content-Security-Policy into the built index.html. */
function contentSecurityPolicyMeta(apiBaseUrl) {
  return {
    name: 'athena-content-security-policy',
    apply: 'build',
    transformIndexHtml() {
      return [
        {
          tag: 'meta',
          attrs: { 'http-equiv': 'Content-Security-Policy', content: contentSecurityPolicy(apiBaseUrl) },
          injectTo: 'head-prepend',
        },
      ]
    },
  }
}

export default defineConfig(({ mode }) => {
  const apiBaseUrl = process.env.VITE_API_BASE_URL ?? loadEnv(mode, process.cwd(), 'VITE_').VITE_API_BASE_URL
  checkApiBaseUrl(apiBaseUrl)

  return {
    plugins: [react(), contentSecurityPolicyMeta(apiBaseUrl)],
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/api': { target: apiTarget } },
    },
    preview: {
      port: 4173,
      strictPort: true,
      headers: vercelHeaders,
    },
    build: {
      // Browsers from the last few years on every platform: Chrome and Edge
      // (Windows, macOS, Android), Firefox, Safari on macOS and iPhone/iPad
      // (iOS 15 and later), Samsung Internet. Newer JavaScript and CSS syntax
      // is rewritten for them, and Lightning CSS adds vendor prefixes.
      target: SUPPORTED_BROWSERS,
      cssTarget: SUPPORTED_BROWSERS,
      sourcemap: false,
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.js'],
      include: ['src/**/*.test.{js,jsx}'],
      css: false,
      restoreMocks: true,
    },
  }
})
