import { afterEach, describe, expect, it, vi } from 'vitest'
import { getConfigFile, putConfigFile, toProblems } from '@/api/client'

afterEach(() => {
  vi.unstubAllGlobals()
})

function serve(status: number, body: unknown) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status })))
}

describe('config problems', () => {
  it('puts errors before warnings and keeps their order otherwise', () => {
    expect(toProblems([
      { severity: 'warning', message: 'w1' },
      { severity: 'error', message: 'e1' },
      { severity: 'warning', message: 'w2' },
      { severity: 'error', message: 'e2' },
    ]).map(p => p.message)).toEqual(['e1', 'e2', 'w1', 'w2'])
  })

  it('reads plain strings from an older Cannoli as errors and drops junk', () => {
    expect(toProblems(['old', 5, null, { message: 'no severity' }, { severity: 'warning' }])).toEqual([
      { severity: 'error', message: 'old' },
      { severity: 'error', message: 'no severity' },
    ])
    expect(toProblems(undefined)).toEqual([])
  })

  it('gives a file with its problems typed', async () => {
    serve(200, { name: 'systems.json', text: '{}', problems: [{ severity: 'warning', message: 'w' }, { severity: 'error', message: 'e' }] })
    const file = await getConfigFile('systems.json')
    expect(file.problems).toEqual([{ severity: 'error', message: 'e' }, { severity: 'warning', message: 'w' }])
  })

  it('gives the save result for a refused file too', async () => {
    serve(422, { saved: false, problems: [{ severity: 'error', message: 'systems.json: not valid JSON' }] })
    expect(await putConfigFile('systems.json', '{')).toEqual({
      saved: false,
      problems: [{ severity: 'error', message: 'systems.json: not valid JSON' }],
    })
    serve(200, { saved: true, problems: [{ severity: 'warning', message: 'w' }] })
    expect(await putConfigFile('systems.json', '{}')).toEqual({ saved: true, problems: [{ severity: 'warning', message: 'w' }] })
  })
})
