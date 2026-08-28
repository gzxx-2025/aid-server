import { describe, expect, it, vi } from 'vitest'
import { fetchRemoteMediaBlob, resolveMediaDownloadFilename } from './mediaFileDownload'

describe('media file download', () => {
  it('adds the media extension from a signed URL without changing the title', () => {
    expect(
      resolveMediaDownloadFilename({
        requestedName: '断崖山台_主视图',
        url: 'https://cdn.example.com/assets/scene.png?token=1',
        mimeType: 'image/png'
      })
    ).toBe('断崖山台_主视图.png')
  })

  it('keeps an existing extension and removes invalid filename characters', () => {
    expect(
      resolveMediaDownloadFilename({
        requestedName: '分镜:01?.MP4',
        url: 'https://cdn.example.com/video.mp4'
      })
    ).toBe('分镜_01_.MP4')
  })

  it('does not mistake a dotted title for a media extension', () => {
    expect(
      resolveMediaDownloadFilename({
        requestedName: '场景.v1',
        url: 'https://cdn.example.com/scene.webp'
      })
    ).toBe('场景.v1.webp')
  })

  it('fetches the remote file as a blob instead of navigating to its URL', async () => {
    const blob = new Blob(['image-bytes'], { type: 'image/png' })
    const fetcher = vi.fn(async () =>
      new Response(blob, {
        status: 200,
        headers: { 'content-disposition': 'attachment; filename="scene.png"' }
      })
    )

    const result = await fetchRemoteMediaBlob('https://cdn.example.com/scene.png', fetcher)

    expect(result.blob.type).toBe('image/png')
    expect(await result.blob.text()).toBe('image-bytes')
    expect(result.responseName).toBe('scene.png')
    expect(fetcher).toHaveBeenCalledWith('https://cdn.example.com/scene.png', {
      credentials: 'same-origin'
    })
  })

  it('rejects failed remote responses without a direct-link fallback', async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 403 }))
    await expect(fetchRemoteMediaBlob('https://cdn.example.com/blocked.png', fetcher)).rejects.toThrow(
      'HTTP 403'
    )
  })
})
