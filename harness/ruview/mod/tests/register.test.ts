import { describe, expect, mock, test, tier } from 'claude-code/testing'

tier('user')

const CAPTURE = {
  ok: true,
  packets: 120,
  decodedPackets: 120,
  heartbeatOnlySenders: [{ address: '192.168.1.67', heartbeats: 30 }],
  nodes: [
    { source: 'esp32', nodeId: 42, csiRateHz: 4.7, csiLossFraction: 0, rssiMean: -63, csi: { shape: '1x64' } },
    { source: 'realtek', nodeId: 3, csiRateHz: 348, csiLossFraction: 0.08, rssiMean: -39, csi: { shape: '1x52' } },
  ],
}

describe('register', () => {
  test('/ruview opens the pane, refreshes from the harness CLI, and stops on close', async ($, on) => {
    const runs: (readonly string[])[] = []
    const opened: string[] = []
    const closed: string[] = []
    const statuses: (string | undefined)[] = []
    const clock = mock.clock(on)
    on('session.start', ($, e) => ({ cwd: e.cwd }))
    on('command.register', ($, e) => ({ value: { command: e.name } }))
    on('process.run', ($, e) => {
      runs.push(e.argv)
      return { value: { exitCode: 0, stdout: JSON.stringify(CAPTURE), stderr: '' } }
    })
    on('ui.open', ($, e) => {
      opened.push(e.id)
      return { value: undefined }
    })
    on('ui.close', ($, e) => {
      closed.push(e.id)
      return { value: undefined }
    })
    on('ui.status', ($, e) => {
      statuses.push(e.text)
      return { value: undefined }
    })
    on('ui.invalidate', () => ({ value: undefined }))

    const settle = async () => {
      for (let i = 0; i < 5; i++) await clock.settle()
    }

    await $.session.start({ surface: 'terminal', isInteractive: true, cwd: '/work' })
    const { text } = await $.command.run({ command: 'ruview', args: '', origin: { kind: 'composer' } })
    await settle()

    expect(text).toContain('RuView pane open')
    expect(opened).toEqual(['ruview-live'])
    expect(runs.length).toBeGreaterThanOrEqual(1)
    const argv = runs[0] ?? []
    expect(argv[0]).toBe('node')
    expect(String(argv[1])).toContain('bin/cli.js')
    expect(argv.slice(2)).toEqual(['esp32', '--seconds', '3', '--udp-port', '5005', '--json'])
    expect(statuses.at(-1)).toBe('RuView · 2 nodes · 1 alert')

    const beforeTicks = runs.length
    for (let i = 0; i < 3; i++) {
      clock.advance(15_000)
      await settle()
    }
    expect(runs.length).toBeGreaterThan(beforeTicks)

    await $.command.run({ command: 'ruview', args: '', origin: { kind: 'composer' } })
    await settle()
    expect(closed).toEqual(['ruview-live'])
    const afterClose = runs.length
    for (let i = 0; i < 4; i++) {
      clock.advance(15_000)
      await settle()
    }
    expect(runs.length).toBe(afterClose)
  })

  test('/ruview refresh with no nodes reports the honest failure in the status', async ($, on) => {
    const statuses: (string | undefined)[] = []
    mock.clock(on)
    on('session.start', ($, e) => ({ cwd: e.cwd }))
    on('command.register', ($, e) => ({ value: { command: e.name } }))
    on('process.run', () => ({ value: { exitCode: 1, stdout: JSON.stringify({ ok: false, reason: 'no_packets', packets: 0 }), stderr: '' } }))
    on('ui.status', ($, e) => {
      statuses.push(e.text)
      return { value: undefined }
    })
    on('ui.invalidate', () => ({ value: undefined }))

    await $.session.start({ surface: 'terminal', isInteractive: true, cwd: '/work' })
    const { text } = await $.command.run({ command: 'ruview', args: 'refresh', origin: { kind: 'composer' } })

    expect(text).toBe('RuView · 0 nodes · 1 alert')
    expect(statuses.at(-1)).toBe('RuView · 0 nodes · 1 alert')
  })
})
