<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { EditorSelection, EditorState, Transaction } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { redo, undo } from '@codemirror/commands'
import { startCompletion } from '@codemirror/autocomplete'
import { forceLinting, nextDiagnostic } from '@codemirror/lint'
import { openSearchPanel } from '@codemirror/search'
import { configExtensions, describe, formatPath, type EditorParts } from '@/lib/config/editorSetup'
import type { ConfigData } from '@/lib/config/configData'
import type { Issue } from '@/lib/config/validate'
import type { Description } from '@/lib/config/describe'
import { Lightbulb, Redo2, Search, SkipForward, Undo2, WrapText } from 'lucide-vue-next'

/**
 * The config file editor: CodeMirror with JSON highlighting, folding, search, and completions,
 * checks and explanations from the config schemas and the other files ([data]). Ctrl/Cmd+S asks
 * to save. The toolbar gives phones what keyboards give desktops: suggestions, search, the next
 * problem, undo and redo. The bar underneath explains whatever the cursor is on, since phones
 * can't hover.
 */
const props = defineProps<{
  modelValue: string
  /** The file's name, which picks its schema. */
  file: string
  readonly?: boolean
  label?: string
  data?: ConfigData | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  save: []
  diagnostics: [issues: Issue[]]
}>()

const host = ref<HTMLDivElement>()
const view = shallowRef<EditorView>()
const here = ref<Description | null>(null)
const expanded = ref(false)
const narrow = typeof window !== 'undefined' && window.matchMedia?.('(max-width: 640px)').matches
const wrapped = ref(!!narrow)
let parts: EditorParts | undefined
let hereTimer: ReturnType<typeof setTimeout> | undefined

const EMPTY: ConfigData = { files: {}, hints: null }

function newState(doc: string): EditorState {
  parts = configExtensions(
    {
      file: () => props.file,
      data: () => props.data ?? null,
      onSave: () => emit('save'),
      onDiagnostics: issues => emit('diagnostics', issues),
    },
    !!props.readonly,
    wrapped.value,
  )
  return EditorState.create({
    doc,
    extensions: [
      parts.extensions,
      EditorView.updateListener.of(u => {
        if (u.docChanged) {
          const text = u.state.doc.toString()
          if (text !== props.modelValue) emit('update:modelValue', text)
        }
        if (u.docChanged || u.selectionSet) scheduleHere()
      }),
      EditorView.contentAttributes.of({ 'aria-label': props.label ?? props.file }),
    ],
  })
}

function scheduleHere() {
  clearTimeout(hereTimer)
  hereTimer = setTimeout(() => {
    const v = view.value
    if (!v) return
    here.value = describe(props.file, v.state.doc.toString(), v.state.selection.main.head, props.data ?? EMPTY)
  }, 150)
}

onMounted(() => {
  view.value = new EditorView({ state: newState(props.modelValue), parent: host.value! })
})

onBeforeUnmount(() => {
  clearTimeout(hereTimer)
  view.value?.destroy()
})

// Another file: a fresh state, so undo can't reach into the previous file.
watch(() => [props.file, props.readonly] as const, () => {
  view.value?.setState(newState(props.modelValue))
  here.value = null
})

// Text set from outside (load, revert): replace the document, outside the undo history.
watch(() => props.modelValue, text => {
  const v = view.value
  if (!v || v.state.doc.toString() === text) return
  v.dispatch({
    changes: { from: 0, to: v.state.doc.length, insert: text },
    annotations: Transaction.addToHistory.of(false),
  })
})

watch(() => props.data, () => {
  if (view.value) forceLinting(view.value)
  scheduleHere()
})

function toggleWrap() {
  wrapped.value = !wrapped.value
  view.value?.dispatch({ effects: parts!.wrap.reconfigure(wrapped.value ? EditorView.lineWrapping : []) })
}

/** Runs a command on the editor, keeping its focus (and a phone's keyboard). */
function run(command: (v: EditorView) => boolean) {
  const v = view.value
  if (!v) return
  v.focus()
  command(v)
}

/** Puts the cursor at [offset] and scrolls it into view. */
function focusAt(offset: number) {
  const v = view.value
  if (!v) return
  const pos = Math.min(Math.max(0, offset), v.state.doc.length)
  v.dispatch({ selection: EditorSelection.cursor(pos), effects: EditorView.scrollIntoView(pos, { y: 'center' }) })
  v.focus()
}

function nextProblem() {
  run(nextDiagnostic)
}

defineExpose({ focusAt, nextProblem })
</script>

<template>
  <div class="flex flex-col rounded-lg border border-border bg-surface-sunken overflow-hidden">
    <div
      class="flex items-center gap-0.5 border-b border-border bg-card/60 px-1 py-0.5 text-foreground/75"
      role="toolbar"
      :aria-label="$t('config.editorTools')"
    >
      <button
        v-if="!readonly"
        type="button"
        class="config-tool"
        :title="$t('config.toolSuggest')"
        @pointerdown.prevent
        @click="run(startCompletion)"
      ><Lightbulb class="h-4 w-4" /><span class="hidden sm:inline">{{ $t('config.toolSuggestShort') }}</span></button>
      <button type="button" class="config-tool" :title="$t('config.toolSearch')" @pointerdown.prevent @click="run(openSearchPanel)">
        <Search class="h-4 w-4" /><span class="hidden sm:inline">{{ $t('config.toolSearchShort') }}</span>
      </button>
      <button
        v-if="!readonly"
        type="button"
        class="config-tool"
        :title="$t('config.toolNextProblem')"
        @pointerdown.prevent
        @click="nextProblem"
      ><SkipForward class="h-4 w-4" /><span class="hidden sm:inline">{{ $t('config.toolNextProblemShort') }}</span></button>
      <span class="flex-1" />
      <template v-if="!readonly">
        <button type="button" class="config-tool" :title="$t('config.toolUndo')" :aria-label="$t('config.toolUndo')" @pointerdown.prevent @click="run(undo)">
          <Undo2 class="h-4 w-4" />
        </button>
        <button type="button" class="config-tool" :title="$t('config.toolRedo')" :aria-label="$t('config.toolRedo')" @pointerdown.prevent @click="run(redo)">
          <Redo2 class="h-4 w-4" />
        </button>
      </template>
      <button
        type="button"
        class="config-tool"
        :class="wrapped ? 'text-accent' : ''"
        :title="$t('config.toolWrap')"
        :aria-label="$t('config.toolWrap')"
        :aria-pressed="wrapped"
        @pointerdown.prevent
        @click="toggleWrap"
      ><WrapText class="h-4 w-4" /></button>
    </div>

    <div ref="host" class="config-editor min-h-0 flex-1" />

    <button
      type="button"
      class="border-t border-border bg-card/40 px-3 py-1.5 text-left text-xs text-foreground/70 min-h-[2.25rem]"
      :aria-expanded="expanded"
      @pointerdown.prevent
      @click="expanded = !expanded"
    >
      <template v-if="here">
        <span class="font-mono text-foreground/55">{{ formatPath(here.path) }}</span>
        <span class="mx-1.5 text-foreground/30">·</span>
        <span :class="expanded ? '' : 'line-clamp-1'">
          <span class="font-semibold text-foreground/85">{{ here.title }}</span>
          <template v-for="(line, i) in here.body" :key="i"> — {{ line.replace(/`/g, '') }}</template>
        </span>
      </template>
      <span v-else class="text-foreground/45">{{ readonly ? $t('config.hereReadOnly') : $t('config.hereHint') }}</span>
    </button>
  </div>
</template>

<style>
.config-tool {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border-radius: 0.25rem;
  padding: 0.35rem 0.5rem;
  font-size: 0.75rem;
  line-height: 1;
  transition: background-color 0.15s;
}
.config-tool:hover {
  background-color: var(--color-muted);
  color: var(--color-foreground);
}
.config-editor .cm-editor {
  height: 100%;
}
.config-editor .cm-config-fold {
  display: inline-block;
  width: 1em;
  text-align: center;
  cursor: pointer;
}
/* Fingers need bigger targets than mice: list rows and gutter markers. */
@media (pointer: coarse) {
  .config-editor .cm-tooltip-autocomplete > ul > li {
    padding: 8px 10px !important;
  }
  .config-editor .cm-editor {
    font-size: 14px;
  }
  .config-editor .cm-config-fold {
    width: 1.4em;
  }
  .config-editor .cm-panel.cm-search input,
  .config-editor .cm-panel.cm-search button {
    font-size: 16px;
  }
}
/* iOS zooms the page into any text field under 16px when it gets focus. */
@supports (-webkit-touch-callout: none) {
  .config-editor .cm-editor {
    font-size: 16px;
  }
}
</style>
