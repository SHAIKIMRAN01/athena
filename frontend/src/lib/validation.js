/** Same rules as the backend's RegisterRequest, checked early for quick feedback. */
export function validateRegistration({ fullName, email, password, confirm }) {
  const errors = {}
  if (!fullName.trim()) errors.fullName = 'Full name is required'
  else if (!/^[\p{L}\p{M}][\p{L}\p{M} .'-]*$/u.test(fullName.trim())) {
    errors.fullName = 'Use letters, spaces, apostrophes, dots and hyphens only'
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) errors.email = 'Enter a valid email'
  if (password.length < 8 || password.length > 72) errors.password = 'Use 8 to 72 characters'
  else if (!/\p{L}/u.test(password) || !/\d/.test(password)) {
    errors.password = 'Include at least one letter and one digit'
  }
  if (confirm !== password) errors.confirm = 'The passwords do not match'
  return errors
}
