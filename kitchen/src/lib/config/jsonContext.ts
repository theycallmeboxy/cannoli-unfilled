/**
 * Where the cursor is in a JSON text that is usually half-typed: which value or key it is in, the
 * path to it, and what is around it. A forgiving scanner of its own rather than a syntax tree,
 * because while someone types, strings are unterminated and commas missing, and a tree's error
 * recovery guesses differently each time. Also a small parser that keeps every value's position,
 * for checking valid JSON against a schema.
 */

export type PathSeg = string | number
export type Path = PathSeg[]

export interface Token {
  type: 'string' | 'number' | 'word' | 'punct'
  from: number
  to: number
  /** A string's contents, unescaped; a punctuation mark or a word as written. */
  text: string
  /** False for a string with no closing quote on its line. */
  closed: boolean
}

const PUNCT = '{}[]:,'

/** Reads one token at or after [i], or null at the end. */
export function nextToken(text: string, i: number): Token | null {
  while (i < text.length && ' \t\n\r'.includes(text[i]!)) i++
  if (i >= text.length) return null
  const c = text[i]!
  if (PUNCT.includes(c)) return { type: 'punct', from: i, to: i + 1, text: c, closed: true }
  if (c === '"') {
    let j = i + 1
    while (j < text.length) {
      const d = text[j]!
      if (d === '"') return { type: 'string', from: i, to: j + 1, text: unescape(text.slice(i + 1, j)), closed: true }
      if (d === '\n' || d === '\r') break
      j += d === '\\' ? 2 : 1
    }
    j = Math.min(j, text.length)
    return { type: 'string', from: i, to: j, text: unescape(text.slice(i + 1, j)), closed: false }
  }
  let j = i
  while (j < text.length && !' \t\n\r"'.includes(text[j]!) && !PUNCT.includes(text[j]!)) j++
  const word = text.slice(i, j)
  return { type: /^-?\d/.test(word) ? 'number' : 'word', from: i, to: j, text: word, closed: true }
}

function unescape(raw: string): string {
  if (!raw.includes('\\')) return raw
  try {
    return JSON.parse(`"${raw}"`) as string
  } catch {
    return raw.replace(/\\(.)/g, '$1')
  }
}

function scalarOf(t: Token): unknown {
  if (t.type === 'string') return t.text
  if (t.type === 'number') return Number(t.text)
  if (t.text === 'true') return true
  if (t.text === 'false') return false
  if (t.text === 'null') return null
  return undefined
}

interface Frame {
  type: 'object' | 'array'
  /** This container's key or index in its parent; undefined for the top-level value. */
  seg?: PathSeg
  state: 'key' | 'colon' | 'value' | 'comma'
  key?: string
  index: number
  keys: string[]
  values: Record<string, unknown>
  items: unknown[]
  /** End of the last thing in it (or of its opening bracket), where a missing comma goes. */
  lastEnd: number
}

export interface JsonContext {
  /** A key of an object, a value, or a place where neither can be typed. */
  kind: 'key' | 'value' | 'none'
  /** For a key: the object's path. For a value: the value's own path. */
  path: Path
  /** The string, number or word the cursor is in, if any. */
  token?: Token
  /** What has been typed of [token] before the cursor (inside the quotes, for a string). */
  prefix: string
  /** The range a completion replaces: [token], up to the cursor if it is an unclosed string. */
  from: number
  to: number
  /** The keys of the object the key or value is in, other than the one at the cursor. */
  siblings: string[]
  /** The plain values (text, numbers, booleans) of those keys. */
  siblingValues: Record<string, unknown>
  /** The other plain values of the array the value is in. */
  arrayItems: unknown[]
  /** Where to put a comma the previous item lacks, if it lacks one. */
  commaBefore: number | null
  /** Whether what is inserted needs a comma after it, because something follows. */
  commaAfter: boolean
  /** For a key token: whether a colon already follows it. */
  hasColon: boolean
  /** Whether the value at the cursor is an item of an array. */
  inArray: boolean
}

function pathOf(frames: Frame[]): Path {
  const out: Path = []
  for (const f of frames) if (f.seg !== undefined) out.push(f.seg)
  return out
}

/** Applies a value-like token or an opening bracket's arrival to the innermost frame. */
function valueArrives(frame: Frame | undefined): PathSeg | undefined {
  if (!frame) return undefined
  if (frame.type === 'array') {
    if (frame.state === 'comma') frame.index++
    frame.state = 'comma'
    return frame.index
  }
  frame.state = 'comma'
  return frame.key
}

/** Analyses [text] at cursor position [pos]. */
export function analyze(text: string, pos: number): JsonContext {
  const frames: Frame[] = []
  let current: Token | undefined
  let i = 0
  for (;;) {
    const t = nextToken(text, i)
    if (!t || t.from >= pos) break
    const inside = t.type !== 'punct' && t.from < pos && (pos < t.to || (pos === t.to && (t.type !== 'string' || !t.closed)))
    if (inside) {
      current = t
      break
    }
    i = t.to
    const top = frames[frames.length - 1]
    if (t.type === 'punct') {
      if (t.text === '{' || t.text === '[') {
        const seg = valueArrives(top)
        frames.push({
          type: t.text === '{' ? 'object' : 'array',
          seg: top ? seg : undefined,
          state: t.text === '{' ? 'key' : 'value',
          index: 0,
          keys: [],
          values: {},
          items: [],
          lastEnd: t.to,
        })
      } else if (t.text === '}' || t.text === ']') {
        frames.pop()
        const parent = frames[frames.length - 1]
        if (parent) {
          parent.state = 'comma'
          parent.lastEnd = t.to
        }
      } else if (t.text === ':') {
        if (top?.type === 'object') top.state = 'value'
      } else if (t.text === ',') {
        if (top?.type === 'object') top.state = 'key'
        else if (top?.type === 'array') {
          top.index++
          top.state = 'value'
        }
        if (top) top.lastEnd = t.to
      }
      continue
    }
    if (!top) continue
    if (top.type === 'object' && (top.state === 'key' || top.state === 'comma')) {
      top.key = t.text
      top.keys.push(t.text)
      top.state = 'colon'
      top.lastEnd = t.to
    } else if (top.type === 'object') {
      if (top.key !== undefined) top.values[top.key] = scalarOf(t)
      top.state = 'comma'
      top.lastEnd = t.to
    } else {
      valueArrives(top)
      top.items.push(scalarOf(t))
      top.lastEnd = t.to
    }
  }

  const top = frames[frames.length - 1]
  const tok = current
  const from = tok ? tok.from : pos
  const to = tok ? (tok.type === 'string' && !tok.closed ? pos : tok.to) : pos
  const prefix = tok ? text.slice(tok.from + (tok.type === 'string' ? 1 : 0), pos) : ''
  const base = {
    token: tok,
    prefix: tok?.type === 'string' ? unescape(prefix) : prefix,
    from,
    to,
    siblings: [] as string[],
    siblingValues: {} as Record<string, unknown>,
    arrayItems: [] as unknown[],
    commaBefore: null as number | null,
    commaAfter: false,
    hasColon: false,
    inArray: false,
  }
  if (!top) return { ...base, kind: 'none', path: [] }

  const after = scanAfter(text, to, top.type)
  const next = nextToken(text, to)
  base.commaAfter = !!next && !(next.type === 'punct' && ',}]:'.includes(next.text))
  const lacksComma = top.state === 'comma'

  if (top.type === 'array') {
    const index = top.state === 'comma' ? top.index + 1 : top.index
    return {
      ...base,
      kind: top.state === 'value' || top.state === 'comma' ? 'value' : 'none',
      path: [...pathOf(frames), index],
      arrayItems: [...top.items, ...after.items],
      commaBefore: lacksComma ? top.lastEnd : null,
      inArray: true,
    }
  }

  const siblingsBefore = top.keys
  if (top.state === 'key' || top.state === 'comma') {
    const hasColon = next?.type === 'punct' && next.text === ':'
    return {
      ...base,
      kind: 'key',
      path: pathOf(frames),
      siblings: [...siblingsBefore, ...after.keys],
      siblingValues: { ...top.values, ...after.values },
      commaBefore: lacksComma ? top.lastEnd : null,
      // A key followed by its colon keeps its value: no comma to add.
      commaAfter: hasColon ? false : base.commaAfter,
      hasColon,
    }
  }
  if (top.state === 'value' && top.key !== undefined) {
    const others = siblingsBefore.filter(k => k !== top.key)
    const values = { ...top.values, ...after.values }
    delete values[top.key]
    return {
      ...base,
      kind: 'value',
      path: [...pathOf(frames), top.key],
      siblings: [...others, ...after.keys],
      siblingValues: values,
    }
  }
  return { ...base, kind: 'none', path: pathOf(frames) }
}

/** The keys and plain values of the rest of the object (or items of the array) after [from]. */
function scanAfter(text: string, from: number, type: 'object' | 'array') {
  const keys: string[] = []
  const values: Record<string, unknown> = {}
  const items: unknown[] = []
  let depth = 0
  let i = from
  let pendingKey: string | undefined
  let expectValue = false
  let prev: Token | null = null
  for (;;) {
    const t = nextToken(text, i)
    if (!t) break
    i = t.to
    if (t.type === 'punct') {
      if (t.text === '{' || t.text === '[') depth++
      else if (t.text === '}' || t.text === ']') {
        depth--
        if (depth < 0) break
      } else if (depth === 0 && t.text === ':' && prev?.type === 'string' && type === 'object') {
        pendingKey = prev.text
        keys.push(prev.text)
        expectValue = true
        prev = t
        continue
      }
      expectValue = false
      prev = t
      continue
    }
    if (depth === 0) {
      if (type === 'object' && expectValue && pendingKey !== undefined) {
        values[pendingKey] = scalarOf(t)
        expectValue = false
      } else if (type === 'array') {
        items.push(scalarOf(t))
      }
    }
    prev = t
  }
  return { keys, values, items }
}

/** The current line's leading whitespace at [pos]. */
export function indentAt(text: string, pos: number): string {
  const start = text.lastIndexOf('\n', pos - 1) + 1
  return /^[ \t]*/.exec(text.slice(start))![0]
}

// --- Positioned parse of valid JSON ---

export type PNode =
  | { type: 'object'; from: number; to: number; props: PProp[] }
  | { type: 'array'; from: number; to: number; items: PNode[] }
  | { type: 'string'; from: number; to: number; value: string }
  | { type: 'number'; from: number; to: number; value: number }
  | { type: 'boolean'; from: number; to: number; value: boolean }
  | { type: 'null'; from: number; to: number; value: null }

export interface PProp {
  key: string
  keyFrom: number
  keyTo: number
  value: PNode
}

/**
 * Parses JSON text keeping each value's and key's position. The text must be valid (see
 * findJsonError); anything it can't read gives null.
 */
export function parseTree(text: string): PNode | null {
  let i = 0
  const peek = () => nextToken(text, i)
  const take = () => {
    const t = nextToken(text, i)
    if (!t) throw new Error('end')
    i = t.to
    return t
  }
  const value = (): PNode => {
    const t = take()
    if (t.type === 'string') return { type: 'string', from: t.from, to: t.to, value: t.text }
    if (t.type === 'number') return { type: 'number', from: t.from, to: t.to, value: Number(t.text) }
    if (t.type === 'word') {
      if (t.text === 'null') return { type: 'null', from: t.from, to: t.to, value: null }
      return { type: 'boolean', from: t.from, to: t.to, value: t.text === 'true' }
    }
    if (t.text === '{') {
      const props: PProp[] = []
      if (peek()?.text === '}' && peek()?.type === 'punct') {
        const end = take()
        return { type: 'object', from: t.from, to: end.to, props }
      }
      for (;;) {
        const k = take()
        take() // :
        props.push({ key: k.text, keyFrom: k.from, keyTo: k.to, value: value() })
        const sep = take()
        if (sep.text === '}') return { type: 'object', from: t.from, to: sep.to, props }
      }
    }
    if (t.text === '[') {
      const items: PNode[] = []
      if (peek()?.text === ']' && peek()?.type === 'punct') {
        const end = take()
        return { type: 'array', from: t.from, to: end.to, items }
      }
      for (;;) {
        items.push(value())
        const sep = take()
        if (sep.text === ']') return { type: 'array', from: t.from, to: sep.to, items }
      }
    }
    throw new Error('unexpected')
  }
  try {
    return value()
  } catch {
    return null
  }
}

/** The plain JavaScript value of [node]. */
export function plain(node: PNode): unknown {
  switch (node.type) {
    case 'object': {
      const out: Record<string, unknown> = {}
      for (const p of node.props) out[p.key] = plain(p.value)
      return out
    }
    case 'array':
      return node.items.map(plain)
    default:
      return node.value
  }
}
