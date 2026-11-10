import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { expect, it, vi } from 'vitest'
import Dialog from './Dialog'

/** A page that, like the real ones, passes a new onClose function on every render. */
function PageWithDialog({ onClose = () => {} }) {
  const [reason, setReason] = useState('')
  return (
    <>
      <button type="button">Before</button>
      <Dialog open title="Freeze account?" onClose={() => onClose()}>
        <label>
          Reason
          <input value={reason} onChange={(event) => setReason(event.target.value)} />
        </label>
      </Dialog>
    </>
  )
}

it('keeps the focus in a field while typing, even though the page re-renders', async () => {
  render(<PageWithDialog />)

  await userEvent.type(screen.getByLabelText('Reason'), 'Suspicious activity')

  expect(screen.getByLabelText('Reason')).toHaveValue('Suspicious activity')
  expect(screen.getByLabelText('Reason')).toHaveFocus()
})

it('moves the focus into the dialog when it opens and closes on Escape', async () => {
  const onClose = vi.fn()
  render(<PageWithDialog onClose={onClose} />)

  expect(screen.getByRole('dialog', { name: 'Freeze account?' })).toHaveFocus()
  await userEvent.keyboard('{Escape}')
  expect(onClose).toHaveBeenCalledTimes(1)
})
