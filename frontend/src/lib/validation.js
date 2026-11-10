// The backend's rules, checked early for quick feedback. The server checks
// everything again; these only save a round trip.

function nameProblem(fullName) {
  if (!fullName.trim()) return 'Full name is required'
  if (!/^[\p{L}\p{M}][\p{L}\p{M} .'-]*$/u.test(fullName.trim())) {
    return 'Use letters, spaces, apostrophes, dots and hyphens only'
  }
  return null
}

function emailProblem(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()) ? null : 'Enter a valid email'
}

/** Customers need 8 characters; staff (administrators, auditors) need 12. */
function passwordProblem(password, minimum = 8) {
  if (password.length < minimum || password.length > 72) return `Use ${minimum} to 72 characters`
  if (!/\p{L}/u.test(password) || !/\d/.test(password)) return 'Include at least one letter and one digit'
  return null
}

function collect(problems) {
  return Object.fromEntries(Object.entries(problems).filter(([, problem]) => problem))
}

/** Same rules as the backend's RegisterRequest. */
export function validateRegistration({ fullName, email, password, confirm }) {
  return collect({
    fullName: nameProblem(fullName),
    email: emailProblem(email),
    password: passwordProblem(password),
    confirm: confirm !== password ? 'The passwords do not match' : null,
  })
}

/** Same rules as the backend's ChangePasswordRequest; `minimum` is 12 for staff. */
export function validatePasswordChange({ currentPassword, newPassword, confirm }, minimum = 8) {
  return collect({
    currentPassword: currentPassword ? null : 'Enter your current password',
    newPassword:
      passwordProblem(newPassword, minimum) ??
      (newPassword === currentPassword ? 'Choose a password different from the current one' : null),
    confirm: confirm !== newPassword ? 'The passwords do not match' : null,
  })
}

/** Same rules as the backend's CreateStaffRequest. */
export function validateStaff({ fullName, email, role, initialPassword }) {
  return collect({
    fullName: nameProblem(fullName),
    email: emailProblem(email),
    role: ['ADMIN', 'AUDITOR'].includes(role) ? null : 'Choose a role',
    initialPassword: passwordProblem(initialPassword, 12),
  })
}

/** A random initial password for a new staff member: 16 letters and digits, with both. */
export function generatePassword(length = 16) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  const values = crypto.getRandomValues(new Uint32Array(length))
  let password = Array.from(values, (value) => alphabet[value % alphabet.length]).join('')
  if (!/\d/.test(password)) password = password.slice(0, -1) + '7'
  if (!/\p{L}/u.test(password)) password = 'k' + password.slice(1)
  return password
}
