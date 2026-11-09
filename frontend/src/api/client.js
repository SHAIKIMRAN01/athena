import axios from 'axios'
import { apiBaseUrl } from './baseUrl'

/**
 * JSON.parse that keeps decimal numbers as their exact text: "1000.00" from
 * the server stays "1000.00" instead of becoming the float 1000. Whole
 * numbers (ids, counts) stay numbers. Uses the JSON.parse source-text
 * feature of current browsers; older ones fall back to normal numbers.
 */
export function parseJsonExact(text) {
  if (typeof text !== 'string' || text.trim() === '') return text
  try {
    return JSON.parse(text, (key, value, context) => {
      if (typeof value === 'number' && context?.source && /[.eE]/.test(context.source)) {
        return context.source
      }
      return value
    })
  } catch {
    return text
  }
}

/** An error the pages can show: status, machine code, human message, field errors. */
export class ApiError extends Error {
  constructor({ status, code, message, fieldErrors = {}, retryAfter = null, data = null }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.retryAfter = retryAfter
    this.data = data
  }

  /** True when the request may or may not have reached the server. */
  get outcomeUnknown() {
    return this.status === 0
  }
}

export function toApiError(error) {
  if (error instanceof ApiError) return error

  const response = error?.response
  if (!response) {
    const timedOut = error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT'
    return new ApiError({
      status: 0,
      code: timedOut ? 'TIMEOUT' : 'NETWORK_ERROR',
      message: timedOut
        ? 'The server took too long to answer. Your request may still have gone through; check your history before retrying.'
        : 'Cannot reach the server. Check your connection and try again.',
    })
  }

  const body = response.data && typeof response.data === 'object' ? response.data : {}
  const retryAfter = Number(response.headers?.['retry-after']) || null

  let message = body.detail || defaultMessage(response.status)
  if (response.status === 429 && retryAfter) {
    message = `${message} (wait about ${Math.ceil(retryAfter / 60)} min)`
  }

  return new ApiError({
    status: response.status,
    code: body.code || `HTTP_${response.status}`,
    message,
    fieldErrors: body.errors || {},
    retryAfter,
    data: body,
  })
}

function defaultMessage(status) {
  if (status === 401) return 'Please sign in.'
  if (status === 403) return 'You do not have permission to do this.'
  if (status === 404) return 'Not found.'
  if (status >= 500) return 'Something went wrong on our side. Please try again.'
  return 'The request could not be completed.'
}

export const api = axios.create({
  baseURL: apiBaseUrl(import.meta.env.VITE_API_BASE_URL),
  timeout: 15_000,
  transformResponse: [parseJsonExact],
  headers: { Accept: 'application/json' },
})

let getToken = () => null
let onSessionEnded = () => {}

/** Called once by AuthProvider so every request carries the current token. */
export function configureAuth(handlers) {
  getToken = handlers.getToken
  onSessionEnded = handlers.onSessionEnded
}

api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const apiError = toApiError(error)
    const sentToken = Boolean(error?.config?.headers?.Authorization)
    // A token the server no longer accepts (expired, signed out, user
    // disabled): end the session and send the user to the sign-in page.
    if (apiError.status === 401 && sentToken) onSessionEnded(apiError.code)
    return Promise.reject(apiError)
  },
)

/** Sends a JSON body exactly as written (used for amounts, so no float conversion happens). */
export function rawJson(text) {
  return { data: text, headers: { 'Content-Type': 'application/json' } }
}
