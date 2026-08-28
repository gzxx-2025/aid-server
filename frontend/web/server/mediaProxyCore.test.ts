import { afterEach, describe, expect, it, vi } from 'vitest'
import { handleMediaProxyRequest, isBlockedMediaHostname } from './mediaProxyCore'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('media proxy host validation', () => {
  it.each([
    'localhost',
    'service.internal',
    '127.0.0.1',
    '10.1.2.3',
    '169.254.169.254',
    '192.168.1.1',
    '::1',
    '::ffff:127.0.0.1',
    'fd00::1',
    'fe80::1',
    'ff02::1'
  ])('blocks non-public host %s', (host) => {
    expect(isBlockedMediaHostname(host)).toBe(true)
  })

  it.each(['cdn.example.com', '8.8.8.8', '2606:4700:4700::1111'])(
    'allows public-looking host %s',
    (host) => {
      expect(isBlockedMediaHostname(host)).toBe(false)
    }
  )

  it('rejects a private target before requesting upstream media', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const response = await handleMediaProxyRequest(
      new Request('http://studio.local/media/proxy?url=http%3A%2F%2F127.0.0.1%2Fvideo.mp4')
    )

    expect(response.status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('passes Range through and streams a partial media response', async () => {
    const fetchMock = vi.fn(async (_url: URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).get('range')).toBe('bytes=0-3')
      return new Response(new Uint8Array([0, 1, 2, 3]), {
        status: 206,
        headers: {
          'content-type': 'video/mp4',
          'content-range': 'bytes 0-3/8',
          'accept-ranges': 'bytes'
        }
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const response = await handleMediaProxyRequest(
      new Request(
        'http://studio.local/media/proxy?url=https%3A%2F%2Fcdn.example.com%2Fvideo.mp4',
        { headers: { range: 'bytes=0-3' } }
      )
    )

    expect(response.status).toBe(206)
    expect(response.headers.get('content-range')).toBe('bytes 0-3/8')
    expect(Array.from(new Uint8Array(await response.arrayBuffer()))).toEqual([0, 1, 2, 3])
  })
})
