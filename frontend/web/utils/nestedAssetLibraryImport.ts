export interface NestedAssetLibraryImportOptions<T> {
  closeAssetLibrary: () => void
  importAsset: (payload: T) => Promise<boolean>
  closeOwner: () => void
  payload: T
}

/**
 * Nested Ant Design Dialogs lose click handlers if the owner closes while the
 * asset-library portal is still open. Close the nested portal first, persist,
 * then close the owner only after persistence accepts the payload.
 */
export async function runNestedAssetLibraryImport<T>(
  options: NestedAssetLibraryImportOptions<T>
): Promise<boolean> {
  options.closeAssetLibrary()
  const accepted = await options.importAsset(options.payload)
  if (!accepted) return false
  options.closeOwner()
  return true
}
