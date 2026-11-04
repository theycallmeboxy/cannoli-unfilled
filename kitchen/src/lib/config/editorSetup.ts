/**
 * The CodeMirror setup for the config editor: JSON highlighting in Kitchen's colours, folding,
 * bracket matching, search, and the schema-driven completions, checks and hover from
 * suggest.ts, validate.ts and describe.ts. Only CodeEditor.vue imports this, so it all stays in
 * the editor's own chunk.
 */

import { Compartment, EditorState, type Extension } from '@codemirror/state'
import {
  Decoration,
  EditorView,
  MatchDecorator,
  ViewPlugin,
  drawSelection,
  dropCursor,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  hoverTooltip,
  keymap,
  lineNumbers,
  type DecorationSet,
  type ViewUpdate,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { json } from '@codemirror/lang-json'
import { HighlightStyle, bracketMatching, foldGutter, foldKeymap, indentOnInput, indentUnit, syntaxHighlighting } from '@codemirror/language'
import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  pickedCompletion,
  startCompletion,
  type Completion,
  type CompletionContext,
  type CompletionResult,
} from '@codemirror/autocomplete'
import { linter, lintGutter, lintKeymap, type Diagnostic } from '@codemirror/lint'
import { highlightSelectionMatches, search, searchKeymap } from '@codemirror/search'
import { tags as t } from '@lezer/highlight'
import { suggest, type SuggestResult, type Suggestion } from './suggest'
import { validate, type Issue } from './validate'
import { describe, formatPath, type Description } from './describe'
import type { ConfigData } from './configData'

export interface EditorHooks {
  file: () => string
  data: () => ConfigData | null
  onSave: () => void
  onDiagnostics: (issues: Issue[]) => void
}

const EMPTY: ConfigData = { files: {}, hints: null }

/** Text with `code` in backticks, as DOM, one paragraph per line. */
export function richText(text: string, into: HTMLElement): HTMLElement {
  for (const line of text.split('\n')) {
    const p = document.createElement('p')
    line.split(/(`[^`]+`)/).forEach(part => {
      if (part.startsWith('`') && part.endsWith('`') && part.length > 1) {
        const code = document.createElement('code')
        code.textContent = part.slice(1, -1)
        p.appendChild(code)
      } else if (part) {
        p.appendChild(document.createTextNode(part))
      }
    })
    into.appendChild(p)
  }
  return into
}

function toCompletion(o: Suggestion, r: SuggestResult): Completion {
  return {
    label: o.label,
    detail: o.detail,
    info: o.info ? () => richText(o.info!, document.createElement('div')) : undefined,
    type: o.type,
    boost: o.boost,
    section: o.section,
    apply: (view, completion, _from, to) => {
      const changes = [{ from: r.from, to: Math.max(r.to, to), insert: o.insert + (r.commaAfter ? ',' : '') }]
      if (r.commaBefore !== null && r.commaBefore <= r.from) changes.unshift({ from: r.commaBefore, to: r.commaBefore, insert: ',' })
      const set = view.state.changes(changes)
      const start = set.mapPos(r.from, -1)
      view.dispatch({
        changes: set,
        selection: { anchor: start + (o.cursor ?? o.insert.length) },
        scrollIntoView: true,
        userEvent: 'input.complete',
        annotations: pickedCompletion.of(completion),
      })
      if (o.reopen) setTimeout(() => startCompletion(view), 0)
    },
  }
}

function completionSource(hooks: EditorHooks) {
  return (cx: CompletionContext): CompletionResult | null => {
    if (cx.state.readOnly) return null
    const r = suggest(hooks.file(), cx.state.doc.toString(), cx.pos, hooks.data() ?? EMPTY, cx.explicit)
    if (!r || !r.options.length) return null
    return { from: r.filterFrom, options: r.options.map(o => toCompletion(o, r)) }
  }
}

function describeDom(d: Description): HTMLElement {
  const dom = document.createElement('div')
  dom.className = 'cm-config-doc'
  const title = document.createElement('div')
  title.className = 'cm-config-doc-title'
  title.textContent = d.title
  dom.appendChild(title)
  if (d.path.length > 1) {
    const path = document.createElement('div')
    path.className = 'cm-config-doc-path'
    path.textContent = formatPath(d.path)
    dom.appendChild(path)
  }
  for (const line of d.body) richText(line, dom)
  return dom
}

/** `"_note": "…"` and other comment keys, dimmed like comments. */
const commentDecorator = new MatchDecorator({
  regexp: /"_(?:[^"\\\n]|\\.)*"\s*:\s*(?:"(?:[^"\\\n]|\\.)*")?/g,
  decoration: Decoration.mark({ class: 'cm-config-comment' }),
})
const comments = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet
    constructor(view: EditorView) {
      this.decorations = commentDecorator.createDeco(view)
    }
    update(u: ViewUpdate) {
      this.decorations = commentDecorator.updateDeco(u, this.decorations)
    }
  },
  { decorations: v => v.decorations },
)

const highlight = HighlightStyle.define([
  { tag: t.propertyName, color: 'var(--color-accent, #d9a06a)' },
  { tag: t.string, color: 'var(--color-primary-light, #bccc9f)' },
  { tag: t.number, color: 'var(--color-cream, #f5e6ca)' },
  { tag: [t.bool, t.null], color: '#8ec5d6', fontWeight: '600' },
  { tag: [t.brace, t.squareBracket], color: 'color-mix(in srgb, var(--color-foreground, #fff) 70%, transparent)' },
  { tag: t.separator, color: 'color-mix(in srgb, var(--color-foreground, #fff) 45%, transparent)' },
  { tag: t.invalid, color: 'var(--color-destructive, #e05872)' },
])

const mix = (color: string, pct: number) => `color-mix(in srgb, ${color} ${pct}%, transparent)`
const FG = 'var(--color-foreground, #fff)'
const PRIMARY = 'var(--color-primary, #a3b87f)'

/** Kitchen's look, from its CSS variables. Kitchen is dark-only today; the variables carry any change. */
const theme = EditorView.theme(
  {
    '&': { color: FG, backgroundColor: 'var(--color-surface-sunken, #0e0e10)', fontSize: '13px', height: '100%' },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace', lineHeight: '1.6' },
    '.cm-content': { caretColor: PRIMARY, padding: '8px 0' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: PRIMARY, borderLeftWidth: '2px' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection':
      { backgroundColor: mix(PRIMARY, 30) },
    '.cm-gutters': { backgroundColor: mix('var(--color-card, #24211c)', 45), color: mix(FG, 35), borderRight: '1px solid var(--color-border, #322d24)' },
    '.cm-activeLine': { backgroundColor: mix(FG, 4) },
    '.cm-activeLineGutter': { backgroundColor: mix(FG, 6), color: mix(FG, 70) },
    '.cm-foldPlaceholder': { backgroundColor: 'var(--color-muted, #2a2620)', border: 'none', color: mix(FG, 70), padding: '0 6px' },
    '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': { backgroundColor: mix(PRIMARY, 25), outline: `1px solid ${mix(PRIMARY, 50)}` },
    '.cm-nonmatchingBracket, &.cm-focused .cm-nonmatchingBracket': { backgroundColor: mix('var(--color-destructive, #e05872)', 30) },
    '.cm-searchMatch': { backgroundColor: mix('var(--color-accent, #d9a06a)', 25), outline: `1px solid ${mix('var(--color-accent, #d9a06a)', 50)}` },
    '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: mix('var(--color-accent, #d9a06a)', 50) },
    '.cm-selectionMatch': { backgroundColor: mix(FG, 10) },
    '.cm-tooltip': {
      backgroundColor: 'var(--color-card, #24211c)',
      color: FG,
      border: '1px solid var(--color-border, #322d24)',
      borderRadius: '6px',
      boxShadow: '0 8px 24px rgba(0,0,0,.45)',
      overflow: 'hidden',
    },
    '.cm-tooltip-autocomplete > ul': { fontFamily: 'inherit', maxHeight: 'min(18rem, 40vh)' },
    '.cm-tooltip-autocomplete > ul > li': { padding: '3px 8px', lineHeight: '1.5' },
    '.cm-tooltip-autocomplete > ul > li[aria-selected]': { backgroundColor: mix(PRIMARY, 28), color: FG },
    '.cm-tooltip-autocomplete > ul > completion-section': {
      color: mix(FG, 55), borderBottom: '1px solid var(--color-border, #322d24)', fontSize: '11px', padding: '4px 8px 2px', textTransform: 'uppercase', letterSpacing: '.04em',
    },
    '.cm-completionLabel': { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace' },
    '.cm-completionDetail': { color: mix(FG, 55), fontStyle: 'normal', marginLeft: '0.8em' },
    '.cm-completionMatchedText': { textDecoration: 'none', color: 'var(--color-accent, #d9a06a)', fontWeight: '700' },
    '.cm-completionInfo': { padding: '6px 10px', maxWidth: '22rem', fontSize: '12px', lineHeight: '1.45' },
    '.cm-completionInfo p, .cm-config-doc p': { margin: '0 0 4px' },
    '.cm-completionInfo code, .cm-config-doc code, .cm-diagnostic code': { fontFamily: 'ui-monospace, monospace', backgroundColor: mix(FG, 8), padding: '0 3px', borderRadius: '3px' },
    '.cm-config-doc': { padding: '6px 10px', maxWidth: '24rem', fontSize: '12px', lineHeight: '1.45' },
    '.cm-config-doc-title': { fontWeight: '700', marginBottom: '2px' },
    '.cm-config-doc-path': { fontFamily: 'ui-monospace, monospace', fontSize: '11px', color: mix(FG, 50), marginBottom: '4px' },
    '.cm-config-comment, .cm-config-comment span': { color: `${mix(FG, 45)} !important`, fontStyle: 'italic' },
    '.cm-diagnostic': { padding: '4px 8px', fontSize: '12px' },
    '.cm-diagnostic-error': { borderLeft: '4px solid var(--color-destructive, #e05872)' },
    '.cm-diagnostic-warning': { borderLeft: '4px solid #f59e0b' },
    '.cm-diagnostic-info': { borderLeft: `4px solid ${PRIMARY}` },
    '.cm-lintRange-info': { backgroundImage: 'none', textDecoration: `underline dotted ${mix(PRIMARY, 80)}`, textUnderlineOffset: '3px' },
    '.cm-panels': { backgroundColor: 'var(--color-card, #24211c)', color: FG },
    '.cm-panels.cm-panels-bottom': { borderTop: '1px solid var(--color-border, #322d24)' },
    '.cm-panels.cm-panels-top': { borderBottom: '1px solid var(--color-border, #322d24)' },
    '.cm-textfield': {
      backgroundColor: 'var(--color-surface-sunken, #0e0e10)', color: FG, border: '1px solid var(--color-border, #322d24)', borderRadius: '4px', fontSize: '14px', padding: '3px 6px',
    },
    '.cm-button': { backgroundImage: 'none', backgroundColor: 'var(--color-muted, #2a2620)', color: FG, border: '1px solid var(--color-border, #322d24)', borderRadius: '4px' },
    '.cm-panel.cm-search label': { fontSize: '13px' },
    '.cm-panel button[name=close]': { color: FG, fontSize: '18px' },
  },
  { dark: true },
)

export interface EditorParts {
  extensions: Extension[]
  /** Holds EditorView.lineWrapping, or nothing. */
  wrap: Compartment
}

/** The extensions for a config editor; [readOnly] for a template. */
export function configExtensions(hooks: EditorHooks, readOnly: boolean, wrapLines: boolean): EditorParts {
  const wrap = new Compartment()
  const extensions: Extension[] = [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    foldGutter({ markerDOM: open => {
      const el = document.createElement('span')
      el.className = 'cm-config-fold'
      el.textContent = open ? '▾' : '▸'
      return el
    } }),
    drawSelection(),
    dropCursor(),
    indentUnit.of('  '),
    EditorState.tabSize.of(2),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    json(),
    syntaxHighlighting(highlight),
    bracketMatching(),
    closeBrackets(),
    highlightActiveLine(),
    highlightSelectionMatches(),
    search({ top: true }),
    comments,
    theme,
    wrap.of(wrapLines ? EditorView.lineWrapping : []),
    readOnlyExtension(readOnly),
    autocompletion({
      override: [completionSource(hooks)],
      activateOnTyping: true,
      icons: false,
      maxRenderedOptions: 200,
      closeOnBlur: true,
    }),
    linter(view => {
      const issues = validate(hooks.file(), view.state.doc.toString(), hooks.data())
      hooks.onDiagnostics(issues)
      const length = view.state.doc.length
      return issues.map((i): Diagnostic => ({
        from: Math.min(i.from, length),
        to: Math.min(i.to, length),
        severity: i.severity,
        message: i.message,
        renderMessage: () => richText(i.message, document.createElement('div')),
      }))
    }, { delay: 300 }),
    lintGutter(),
    hoverTooltip((view, pos) => {
      const d = describe(hooks.file(), view.state.doc.toString(), pos, hooks.data() ?? EMPTY)
      if (!d) return null
      return { pos: d.from, end: d.to, above: true, create: () => ({ dom: describeDom(d) }) }
    }, { hoverTime: 350 }),
    keymap.of([
      { key: 'Mod-s', preventDefault: true, run: () => { hooks.onSave(); return true } },
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...searchKeymap,
      ...historyKeymap,
      ...foldKeymap,
      ...lintKeymap,
      indentWithTab,
    ]),
    EditorView.contentAttributes.of({ autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false', autocomplete: 'off', translate: 'no' }),
  ]
  return { extensions, wrap }
}

/** A template is read-only: selectable and searchable, but phones don't raise a keyboard for it. */
export function readOnlyExtension(readOnly: boolean): Extension {
  return readOnly ? [EditorState.readOnly.of(true), EditorView.contentAttributes.of({ inputmode: 'none' })] : []
}

export { describe, formatPath }
