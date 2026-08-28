export async function readableStreamToBlob(
  stream: ReadableStream<Uint8Array>,
  mimeType: string
): Promise<Blob> {
  const reader = stream.getReader()
  const chunks: BlobPart[] = []
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      if (value) {
        chunks.push(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer)
      }
    }
  } finally {
    try {
      reader.releaseLock()
    } catch {
      // ignore
    }
  }
  return new Blob(chunks, { type: mimeType })
}

