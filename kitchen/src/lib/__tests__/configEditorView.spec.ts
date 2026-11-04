// @vitest-environment happy-dom
/**
 * The real CodeMirror view with the config setup, in a simulated DOM: suggestions open and insert
 * through CodeMirror's own commands, checks reach its lint state, Ctrl+S asks to save.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { acceptCompletion, completionStatus, currentCompletions, moveCompletionSelection, startCompletion } from '@codemirror/autocomplete'
import { diagnosticCount, forceLinting } from '@codemirror/lint'
import { configExtensions } from '@/lib/config/editorSetup'
import { createApp, h, nextTick, ref } from 'vue'
import { i18n } from '@/i18n'
import CodeEditor from '@/components/config/CodeEditor.vue'
import type { Issue } from '@/lib/config/validate'
import { DATA, at } from './configFixture'

let view: EditorView | undefined
afterEach(() => {
  view?.destroy()
  view = undefined
})

function mount(file: string, marked: string, hooks: { onSave?: () => void; onDiagnostics?: (i: Issue[]) => void } = {}) {
  const [doc, pos] = at(marked)
  const parts = configExtensions(
    { file: () => file, data: () => DATA, onSave: hooks.onSave ?? (() => {}), onDiagnostics: hooks.onDiagnostics ?? (() => {}) },
    false,
    false,
  )
  view = new EditorView({ state: EditorState.create({ doc, selection: { anchor: pos }, extensions: parts.extensions }), parent: document.body })
  return view
}

async function until(test: () => boolean, ms = 2000) {
  const end = Date.now() + ms
  while (!test()) {
    if (Date.now() > end) throw new Error('timed out')
    await new Promise(r => setTimeout(r, 10))
  }
}

/** Opens the list, picks [label] and returns the text with `|` at the cursor. */
async function pick(v: EditorView, label: string): Promise<string> {
  startCompletion(v)
  await until(() => completionStatus(v.state) === 'active' && currentCompletions(v.state).length > 0)
  const labels = currentCompletions(v.state).map(c => c.label)
  const index = labels.indexOf(label)
  expect(index, `${label} in ${labels.join(', ')}`).toBeGreaterThanOrEqual(0)
  // CodeMirror ignores moves and accepts in the first 75ms after the list opens.
  await new Promise(r => setTimeout(r, 100))
  for (let i = 0; i < index; i++) moveCompletionSelection(true)(v)
  expect(acceptCompletion(v)).toBe(true)
  const text = v.state.doc.toString()
  const head = v.state.selection.main.head
  return text.slice(0, head) + '|' + text.slice(head)
}

describe('config editor view', () => {
  it('completes a value through CodeMirror', async () => {
    const v = mount('systems.json', '{ "GBA": { "emulators": [ "pi|" ] } }')
    expect(await pick(v, 'pizzaboy')).toBe('{ "GBA": { "emulators": [ "pizzaboy"| ] } }')
  })

  it('completes a key, then offers its values', async () => {
    const v = mount('emulators.json', '{ "x": { "name": "X",\n  |\n} }')
    expect(await pick(v, 'launchMethod')).toBe('{ "x": { "name": "X",\n  "launchMethod": "|"\n} }')
    await until(() => completionStatus(v.state) === 'active' && currentCompletions(v.state).length > 0)
    expect(currentCompletions(v.state).map(c => c.label)).toEqual(['intent', 'shell'])
  })

  it('inserts a whole template entry', async () => {
    const v = mount('emulators.json', '{\n  |\n}')
    const out = await pick(v, 'skyemu')
    expect(JSON.parse(out.replace('|', '')).skyemu.package).toBe('com.sky.SkyEmu')
  })

  it('underlines problems', async () => {
    let seen: Issue[] = []
    const v = mount('systems.json', '{ "GBA": { "rom": "x", "emulators": ["nowhere"] } }|', { onDiagnostics: i => { seen = i } })
    forceLinting(v)
    await until(() => diagnosticCount(v.state) > 0)
    expect(diagnosticCount(v.state)).toBe(2)
    expect(seen.map(i => i.severity)).toEqual(['warning', 'warning'])
  })

  it('saves on Ctrl+S', () => {
    const onSave = vi.fn()
    const v = mount('systems.json', '{}|', { onSave })
    v.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true }))
    expect(onSave).toHaveBeenCalledOnce()
  })

  it('mounts the Vue component, which keeps v-model in step', async () => {
    const text = ref('{ "items": [] }')
    const saves = vi.fn()
    const el = document.createElement('div')
    document.body.appendChild(el)
    const app = createApp({
      render: () => h(CodeEditor, {
        modelValue: text.value,
        'onUpdate:modelValue': (v: string) => { text.value = v },
        onSave: saves,
        file: 'directory.json',
        data: DATA,
      }),
    })
    app.use(i18n)
    app.mount(el)
    await nextTick()
    const cm = EditorView.findFromDOM(el.querySelector('.cm-editor') as HTMLElement)!
    expect(cm.state.doc.toString()).toBe('{ "items": [] }')
    cm.dispatch({ changes: { from: 12, insert: '{ "system": "GBA" }' } })
    expect(text.value).toBe('{ "items": [{ "system": "GBA" }] }')
    text.value = '{}'
    await nextTick()
    expect(cm.state.doc.toString()).toBe('{}')
    expect(el.textContent).toContain('Suggest')
    app.unmount()
  })
})
