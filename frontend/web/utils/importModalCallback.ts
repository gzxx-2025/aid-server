export type ImportModalCallback<T> = (
  payload: T
) => void | boolean | Promise<void | boolean>

export interface ImportModalCallbackResult {
  accepted: boolean
  error?: unknown
}

/** Await persistence before an import modal reports success or closes its owner. */
export async function invokeImportModalCallback<T>(
  callback: ImportModalCallback<T> | undefined,
  payload: T
): Promise<ImportModalCallbackResult> {
  if (!callback) return { accepted: false }
  try {
    return { accepted: (await callback(payload)) !== false }
  } catch (error: unknown) {
    return { accepted: false, error }
  }
}
