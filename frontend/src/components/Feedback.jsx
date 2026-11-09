export function Spinner({ label = 'Loading…' }) {
  return (
    <div className="spinner" role="status" aria-live="polite">
      <span className="spinner-dot" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

/** An error, warning, success or info message. `error` may be an ApiError. */
export function Alert({ kind = 'error', title, error, children, onDismiss }) {
  const message = error?.message ?? children
  if (!message && !title) return null

  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="alert-body">
        {title && <strong className="alert-title">{title}</strong>}
        {message && <span>{message}</span>}
        {error?.data?.reference && (
          <span className="alert-meta">
            Recorded as <code>{error.data.reference}</code>
          </span>
        )}
        {error?.data?.errorId && (
          <span className="alert-meta">
            Error id <code>{error.data.errorId}</code>
          </span>
        )}
      </div>
      {onDismiss && (
        <button type="button" className="alert-close" onClick={onDismiss} aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  )
}

export function EmptyState({ title, children }) {
  return (
    <div className="empty-state">
      <strong>{title}</strong>
      {children && <p>{children}</p>}
    </div>
  )
}
