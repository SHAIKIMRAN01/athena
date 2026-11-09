import { expect, it } from 'vitest'
import { csvCell, statementCsv } from './csv'

it('quotes cells and neutralizes spreadsheet formulas', () => {
  expect(csvCell('Transfer to AT123')).toBe('"Transfer to AT123"')
  expect(csvCell('=HYPERLINK("x")')).toBe('"\'=HYPERLINK(""x"")"')
  expect(csvCell('-5')).toBe('"\'-5"')
  expect(csvCell(null)).toBe('""')
})

it('writes one row per statement line', () => {
  const csv = statementCsv({
    lines: [
      {
        date: '2026-11-08T10:00:00',
        reference: 'TXN0000000001',
        description: 'Cash deposit',
        credit: '100.00',
        debit: null,
        balanceAfter: '100.00',
      },
    ],
  })
  expect(csv.split('\r\n')).toHaveLength(2)
  expect(csv).toContain('"TXN0000000001","Cash deposit","100.00",""')
})
