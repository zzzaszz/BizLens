/**
 * Normalize list responses from endpoints that may return either a plain array
 * or a paginated object with a records field.
 */
export function listRecords(data) {
  if (Array.isArray(data)) return data
  return Array.isArray(data?.records) ? data.records : []
}
