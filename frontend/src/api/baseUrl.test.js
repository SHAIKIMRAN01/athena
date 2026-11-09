import { describe, expect, it } from 'vitest'
import { contentSecurityPolicy } from '../lib/csp'
import { apiBaseUrl, apiOrigin, checkApiBaseUrl } from './baseUrl'

describe('apiBaseUrl', () => {
  it('uses /api on the same origin when nothing is set', () => {
    expect(apiBaseUrl(undefined)).toBe('/api')
    expect(apiBaseUrl('  ')).toBe('/api')
  })

  it('adds /api when it is missing and drops trailing slashes', () => {
    expect(apiBaseUrl('https://athena-api.onrender.com')).toBe('https://athena-api.onrender.com/api')
    expect(apiBaseUrl('https://athena-api.onrender.com/')).toBe('https://athena-api.onrender.com/api')
    expect(apiBaseUrl('https://athena-api.onrender.com/api/')).toBe('https://athena-api.onrender.com/api')
  })

  it('knows the API origin only when it is another site', () => {
    expect(apiOrigin(undefined)).toBeNull()
    expect(apiOrigin('https://athena-api.onrender.com/api')).toBe('https://athena-api.onrender.com')
    expect(apiOrigin('http://localhost:8081')).toBe('http://localhost:8081')
  })
})

describe('checkApiBaseUrl', () => {
  it('accepts https anywhere and http only on this computer', () => {
    expect(() => checkApiBaseUrl('https://athena-api.onrender.com')).not.toThrow()
    expect(() => checkApiBaseUrl('http://localhost:8081')).not.toThrow()
    expect(() => checkApiBaseUrl(undefined)).not.toThrow()
    expect(() => checkApiBaseUrl('http://athena-api.onrender.com')).toThrow(/https/)
  })
})

describe('contentSecurityPolicy', () => {
  it('allows only this site when the API is on the same origin', () => {
    const policy = contentSecurityPolicy(undefined)
    expect(policy).toContain("connect-src 'self';")
    expect(policy).toContain("script-src 'self'")
    expect(policy).not.toContain('unsafe-inline')
  })

  it('allows exactly the API origin when the API is elsewhere', () => {
    expect(contentSecurityPolicy('https://athena-api.onrender.com')).toContain(
      "connect-src 'self' https://athena-api.onrender.com;",
    )
  })
})
