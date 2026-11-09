/**
 * A spreadsheet cell, quoted, and with a leading quote mark if it starts with
 * a character spreadsheets treat as a formula (CSV injection).
 */
export function csvCell(value) {
  let text = value === null || value === undefined ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
  return `"${text.replaceAll('"', '""')}"`
}

export function statementCsv(statement) {
  const rows = [
    ['Date', 'Reference', 'Description', 'Credit', 'Debit', 'Balance'],
    ...statement.lines.map((line) => [line.date, line.reference, line.description, line.credit ?? '', line.debit ?? '', line.balanceAfter]),
  ]
  return rows.map((row) => row.map(csvCell).join(',')).join('\r\n')
}
