import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

function BrokenPage() {
  throw new Error('render failed')
}

it('shows a way back instead of a blank page when a page fails to draw', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {}) // React reports the caught error
  render(
    <ErrorBoundary>
      <BrokenPage />
    </ErrorBoundary>,
  )

  expect(screen.getByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Reload the page' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Go to the start page' })).toHaveAttribute('href', '/')
})

it('shows the page itself when nothing fails', () => {
  render(
    <ErrorBoundary>
      <p>All good</p>
    </ErrorBoundary>,
  )
  expect(screen.getByText('All good')).toBeInTheDocument()
})
