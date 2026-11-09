export default function Pagination({ page, onPageChange }) {
  if (!page || page.totalPages <= 1) {
    return page ? <p className="pagination-summary">{page.totalElements} in total</p> : null
  }

  const current = page.page
  return (
    <nav className="pagination" aria-label="Pages">
      <button type="button" className="button button-ghost" disabled={current === 0} onClick={() => onPageChange(current - 1)}>
        ← Previous
      </button>
      <span>
        Page {current + 1} of {page.totalPages} · {page.totalElements} in total
      </span>
      <button
        type="button"
        className="button button-ghost"
        disabled={current + 1 >= page.totalPages}
        onClick={() => onPageChange(current + 1)}
      >
        Next →
      </button>
    </nav>
  )
}
