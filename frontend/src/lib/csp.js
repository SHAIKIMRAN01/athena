// The Content-Security-Policy for the built website. Used at build time by
// vite.config.js, which writes it into index.html as a <meta> tag.
//
// A <meta> policy is used (instead of only an HTTP header) because the API may
// live on another origin, known only at build time from VITE_API_BASE_URL:
// connect-src must name exactly that origin. The hosting platform adds the
// directives a <meta> tag cannot carry (frame-ancestors) as an HTTP header;
// see vercel.json.

import { apiOrigin } from '../api/baseUrl.js'

export function contentSecurityPolicy(apiBaseUrlValue) {
  const api = apiOrigin(apiBaseUrlValue)
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self'${api ? ` ${api}` : ''}`,
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
  ].join('; ')
}
