import { expect, it } from 'vitest'
import { generatePassword, validatePasswordChange, validateRegistration, validateStaff } from './validation'

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

const change = { currentPassword: 'Password123!', newPassword: 'Brand-new-pass-9', confirm: 'Brand-new-pass-9' }

it('checks a password change like the backend does', () => {
  expect(validatePasswordChange(change)).toEqual({})
  expect(validatePasswordChange({ ...change, currentPassword: '' }).currentPassword).toBeTruthy()
  expect(validatePasswordChange({ ...change, newPassword: 'short1', confirm: 'short1' }).newPassword).toBeTruthy()
  expect(validatePasswordChange({ ...change, confirm: 'Brand-new-pass-8' }).confirm).toBeTruthy()
  expect(validatePasswordChange({ ...change, newPassword: 'Password123!', confirm: 'Password123!' }).newPassword).toBe(
    'Choose a password different from the current one',
  )
})

it('asks staff for 12 characters when they change their password', () => {
  const tenCharacters = { ...change, newPassword: 'Abcdefgh12', confirm: 'Abcdefgh12' }
  expect(validatePasswordChange(tenCharacters)).toEqual({})
  expect(validatePasswordChange(tenCharacters, 12).newPassword).toBe('Use 12 to 72 characters')
})

const auditor = {
  fullName: 'Audrey Auditor',
  email: 'audrey@example.test',
  role: 'AUDITOR',
  initialPassword: 'Initial-pass-2026',
}

it('checks a new staff member like the backend does', () => {
  expect(validateStaff(auditor)).toEqual({})
  expect(validateStaff({ ...auditor, role: 'USER' }).role).toBeTruthy()
  expect(validateStaff({ ...auditor, initialPassword: 'Short-pass1' }).initialPassword).toBeTruthy()
  expect(validateStaff({ ...auditor, fullName: 'R2-D2' }).fullName).toBeTruthy()
})

it('generates initial passwords that pass the staff rule and differ each time', () => {
  const passwords = new Set(Array.from({ length: 50 }, () => generatePassword()))
  expect(passwords.size).toBe(50)
  for (const password of passwords) {
    expect(validateStaff({ ...auditor, initialPassword: password })).toEqual({})
  }
})
