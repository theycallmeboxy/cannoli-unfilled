<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * A plain-text editor for JSON: monospace, line numbers, Tab inserts two spaces, Enter keeps the
 * line's indent, and Ctrl/Cmd+S asks to save. A textarea underneath, so phones get their own
 * keyboard and selection handles.
 */
const props = defineProps<{
  modelValue: string
  readonly?: boolean
  /** 1-based line to mark, such as where a JSON error is. */
  markLine?: number | null
  label?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  save: []
}>()

const INDENT = '  '
const textarea = ref<HTMLTextAreaElement>()
const gutter = ref<HTMLDivElement>()

const lineCount = computed(() => {
  let n = 1
  for (let i = 0; i < props.modelValue.length; i++) if (props.modelValue.charCodeAt(i) === 10) n++
  return n
})

function syncScroll() {
  if (gutter.value && textarea.value) gutter.value.scrollTop = textarea.value.scrollTop
}

/** Replaces the selection, keeping the browser's undo history where it can. */
function insert(text: string, el: HTMLTextAreaElement) {
  // execCommand is deprecated but still the only way to keep undo working in a textarea.
  const ok = typeof document.execCommand === 'function'
    && (text ? document.execCommand('insertText', false, text) : document.execCommand('delete'))
  if (!ok) {
    el.setRangeText(text, el.selectionStart, el.selectionEnd, 'end')
  }
  emit('update:modelValue', el.value)
}

function onKeydown(e: KeyboardEvent) {
  const el = e.target as HTMLTextAreaElement
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    emit('save')
    return
  }
  if (props.readonly) return
  if (e.key === 'Tab' && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    if (e.shiftKey) {
      outdent(el)
    } else {
      insert(INDENT, el)
    }
    return
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey && !e.isComposing) {
    const start = el.selectionStart
    const lineStart = el.value.lastIndexOf('\n', start - 1) + 1
    const indent = /^[ \t]*/.exec(el.value.slice(lineStart, start))![0]
    const before = el.value[start - 1]
    const extra = before === '{' || before === '[' ? INDENT : ''
    e.preventDefault()
    insert(`\n${indent}${extra}`, el)
  }
}

/** Removes up to one indent from the start of the current line. */
function outdent(el: HTMLTextAreaElement) {
  const start = el.selectionStart
  const lineStart = el.value.lastIndexOf('\n', start - 1) + 1
  const lead = /^ {1,2}/.exec(el.value.slice(lineStart))
  if (!lead) return
  el.setSelectionRange(lineStart, lineStart + lead[0].length)
  insert('', el)
  const pos = Math.max(lineStart, start - lead[0].length)
  el.setSelectionRange(pos, pos)
}

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
}

/** Puts the cursor at [offset] and scrolls it into view. */
function focusAt(offset: number) {
  const el = textarea.value
  if (!el) return
  el.focus()
  el.setSelectionRange(offset, Math.min(offset + 1, el.value.length))
  const line = el.value.slice(0, offset).split('\n').length
  const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 20
  el.scrollTop = Math.max(0, (line - 4) * lineHeight)
  syncScroll()
}

defineExpose({ focusAt })
</script>

<template>
  <div class="relative flex rounded-lg border border-border bg-surface-sunken overflow-hidden font-mono text-[13px] leading-5">
    <div
      ref="gutter"
      aria-hidden="true"
      class="shrink-0 select-none overflow-hidden py-2 pl-2 pr-2 text-right text-foreground/35 border-r border-border bg-card/40"
    >
      <div
        v-for="n in lineCount"
        :key="n"
        :class="n === markLine ? 'text-destructive font-bold' : ''"
      >{{ n }}</div>
    </div>
    <textarea
      ref="textarea"
      :value="modelValue"
      :readonly="readonly"
      :aria-label="label"
      wrap="off"
      spellcheck="false"
      autocapitalize="off"
      autocomplete="off"
      autocorrect="off"
      class="flex-1 min-w-0 resize-none bg-transparent py-2 px-3 text-foreground outline-none whitespace-pre overflow-auto"
      @input="onInput"
      @keydown="onKeydown"
      @scroll="syncScroll"
    />
  </div>
</template>
