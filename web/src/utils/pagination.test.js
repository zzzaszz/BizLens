import { describe, expect, it } from 'vitest'
import { listRecords } from './pagination'

describe('listRecords', () => {
  it('unwraps paginated responses', () => {
    expect(listRecords({ records: [{ id: 1 }], total: 1 })).toEqual([{ id: 1 }])
  })

  it('keeps array responses and rejects invalid data', () => {
    const records = [{ id: 1 }]
    expect(listRecords(records)).toBe(records)
    expect(listRecords(null)).toEqual([])
    expect(listRecords({ records: null })).toEqual([])
  })
})
