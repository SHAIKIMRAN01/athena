import { useEffect, useRef } from 'react'

/**
 * A small modal: dims the page, moves focus into the dialog, closes on Esc.
 *
 * Focus moves only when the dialog opens. Pages pass a new onClose function
 * on every render, so the effect must not depend on it: if it did, each
 * keystroke in a field inside the dialog would pull the focus out of it.
 */
export default function Dialog({ open, title, onClose, children, footer }) {
  const panel = useRef(null)
  const latestOnClose = useRef(onClose)

  useEffect(() => {
    latestOnClose.current = onClose
  })

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement
    panel.current?.focus()

    const onKey = (event) => {
      if (event.key === 'Escape') latestOnClose.current?.()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      previous?.focus?.()
    }
  }, [open])

  if (!open) return null

  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title" tabIndex={-1} ref={panel}>
        <header className="dialog-header">
          <h2 id="dialog-title">{title}</h2>
          <button type="button" className="alert-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        <div className="dialog-body">{children}</div>
        {footer && <footer className="dialog-footer">{footer}</footer>}
      </div>
    </div>
  )
}
