import { expect, it } from 'vitest'
import { validateRegistration } from './validation'

const valid = { fullName: "Anaïs D'Souza-Rao", email: 'anais@example.com', password: 'Secret123', confirm: 'Secret123' }

it('accepts a valid registration, including accented names', () => {
  expect(validateRegistration(valid)).toEqual({})
})

it('mirrors the backend rules', () => {
  expect(validateRegistration({ ...valid, fullName: '<b>x</b>' }).fullName).toBeTruthy()
  expect(validateRegistration({ ...valid, email: 'nope' }).email).toBeTruthy()
  expect(validateRegistration({ ...valid, password: 'short1', confirm: 'short1' }).password).toBeTruthy()
  expect(validateRegistration({ ...valid, password: 'lettersonly', confirm: 'lettersonly' }).password).toBeTruthy()
  expect(validateRegistration({ ...valid, confirm: 'Different1' }).confirm).toBeTruthy()
})
