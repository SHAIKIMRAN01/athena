import { useEffect, useRef } from 'react'

/**
 * A small modal: dims the page, moves focus into the dialog, closes on Esc.
 */
export default function Dialog({ open, title, onClose, children, footer }) {
  const panel = useRef(null)

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement
    panel.current?.focus()

    const onKey = (event) => {
      if (event.key === 'Escape') onClose?.()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      previous?.focus?.()
    }
  }, [open, onClose])

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
