<script setup lang="ts">
import { computed, defineAsyncComponent, h, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ApiError, getConfigFile, listConfigFiles, putConfigFile, type ConfigProblem } from '@/api/client'
import { checkJson, isTemplate, templateTarget, type JsonCheck } from '@/lib/configMerge'
import { confirm } from '@/lib/confirm'
import AppHeader from '@/components/layout/AppHeader.vue'
import Button from '@/components/ui/Button.vue'
import { loadConfigData, type ConfigData } from '@/lib/config/configData'
import type { Issue } from '@/lib/config/validate'
import TemplateAdder from '@/components/config/TemplateAdder.vue'
import { ArrowLeft, Braces, CheckCircle2, ChevronRight, Lock, Loader2, RotateCcw, Save, TriangleAlert, XCircle } from 'lucide-vue-next'

const props = defineProps<{ file?: string }>()

/** CodeMirror and the schemas load only once a file is opened, in a chunk of their own. */
const CodeEditor = defineAsyncComponent({
  loader: () => import('@/components/config/CodeEditor.vue'),
  loadingComponent: { render: () => h('p', { class: 'text-sm text-muted-foreground py-6 text-center' }, t('config.editorLoading')) },
  delay: 150,
})
interface EditorHandle {
  focusAt(offset: number): void
  nextProblem(): void
}
const router = useRouter()
const { t } = useI18n()

/** The order people usually need them in: what to run, how to launch it, where it shows. */
const ORDER = [
  'systems.json', 'emulators.json', 'directory.json', 'shortcuts.json', 'overrides.json',
  'systems.template.json', 'emulators.template.json', 'shortcuts.template.json',
]

const files = ref<string[]>([])
const listError = ref('')
const loading = ref(false)
const loadError = ref('')
const text = ref('')
const original = ref('')
const problems = ref<ConfigProblem[]>([])
const problemCounts = computed(() => {
  const error = problems.value.filter(p => p.severity === 'error').length
  return { error, warning: problems.value.length - error }
})
const problemSummary = computed(() => {
  const n = problemCounts.value
  return [
    n.error && t('config.inlineErrors', { n: n.error }, n.error),
    n.warning && t('config.inlineWarnings', { n: n.warning }, n.warning),
  ].filter(Boolean).join(', ')
})
const saving = ref(false)
const status = ref<{ kind: 'ok' | 'warn' | 'error'; text: string } | null>(null)
type TemplateTab = 'entries' | 'text'
const TEMPLATE_TABS: TemplateTab[] = ['entries', 'text']
const templateTab = ref<TemplateTab>('entries')
const editor = ref<EditorHandle | null>(null)
/** The other files, the templates and the device's hints, for suggestions and checks. */
const configData = shallowRef<ConfigData | null>(null)
const inline = ref<Issue[]>([])
const inlineCounts = computed(() => {
  const n = { error: 0, warning: 0, info: 0 }
  for (const i of inline.value) n[i.severity]++
  return n
})
const inlineText = computed(() => {
  const n = inlineCounts.value
  return [
    n.error && t('config.inlineErrors', { n: n.error }, n.error),
    n.warning && t('config.inlineWarnings', { n: n.warning }, n.warning),
    n.info && t('config.inlineNotes', { n: n.info }, n.info),
  ].filter(Boolean).join(' · ')
})

async function refreshData(fresh: boolean) {
  configData.value = await loadConfigData(fresh)
}

const realFiles = computed(() => files.value.filter(f => !isTemplate(f)))
const templateFiles = computed(() => files.value.filter(f => isTemplate(f)))
const current = computed(() => (props.file && files.value.includes(props.file) ? props.file : ''))
const readOnly = computed(() => isTemplate(current.value))
const dirty = computed(() => !readOnly.value && text.value !== original.value)

function describe(name: string): string {
  const key = name.replace(/\.template\.json$/, 'Template').replace(/\.json$/, '')
  const label = t(`config.description.${key}`)
  return label === `config.description.${key}` ? '' : label
}

// Parsing a large file on every keystroke is wasted work; a short pause is enough.
const check = ref<JsonCheck>({ ok: true })
let checkTimer: ReturnType<typeof setTimeout> | undefined
watch(text, value => {
  clearTimeout(checkTimer)
  checkTimer = setTimeout(() => { check.value = checkJson(value) }, 250)
})

const checkText = computed(() => {
  const c = check.value
  if (c.ok) return t('config.validJson')
  if (c.line !== undefined) return t('config.jsonErrorAt', { line: c.line, column: c.column, message: c.message })
  return t('config.jsonError', { message: c.message })
})

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function loadList() {
  try {
    const names = await listConfigFiles()
    files.value = [...names].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))
  } catch (e) {
    listError.value = errorText(e)
  }
}

function rank(name: string): number {
  const i = ORDER.indexOf(name)
  return i < 0 ? ORDER.length + (isTemplate(name) ? 1 : 0) : i
}

async function loadFile(name: string) {
  loading.value = true
  loadError.value = ''
  status.value = null
  try {
    const file = await getConfigFile(name)
    text.value = file.text
    original.value = file.text
    problems.value = file.problems
    check.value = checkJson(file.text)
    inline.value = []
  } catch (e) {
    loadError.value = t('config.loadFailed', { file: name, error: errorText(e) })
    text.value = ''
    original.value = ''
    problems.value = []
  } finally {
    loading.value = false
  }
}

watch(current, name => {
  templateTab.value = 'entries'
  if (name) loadFile(name)
  if (name && !configData.value) refreshData(false)
})

onMounted(async () => {
  await loadList()
  if (current.value) {
    loadFile(current.value)
    refreshData(false)
  }
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  clearTimeout(checkTimer)
})

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (!dirty.value) return
  e.preventDefault()
  e.returnValue = ''
}

async function okToLeave(): Promise<boolean> {
  if (!dirty.value) return true
  return confirm({
    title: t('config.leaveConfirm', { file: current.value }),
    confirmLabel: t('config.leaveConfirmLabel'),
    destructive: true,
  })
}

onBeforeRouteLeave(okToLeave)
onBeforeRouteUpdate(okToLeave)

function open(name: string) {
  router.push({ name: 'tools-config', params: { file: name } })
}

function goToError() {
  if (check.value.offset !== undefined) editor.value?.focusAt(check.value.offset)
}

async function save() {
  if (readOnly.value || saving.value || !current.value) return
  const result = checkJson(text.value)
  check.value = result
  if (!result.ok) {
    status.value = { kind: 'error', text: t('config.fixBeforeSaving') }
    goToError()
    return
  }
  saving.value = true
  const name = current.value
  const sent = text.value
  try {
    const res = await putConfigFile(name, sent)
    problems.value = res.problems
    if (res.saved) {
      original.value = sent
      refreshData(true)
      status.value = res.problems.some(p => p.severity === 'error')
        ? { kind: 'warn', text: t('config.savedWithErrors', { file: name }) }
        : res.problems.length
          ? { kind: 'warn', text: t('config.savedWithWarnings', { file: name }) }
          : { kind: 'ok', text: t('config.saved', { file: name }) }
    } else {
      status.value = { kind: 'error', text: t('config.notSaved', { file: name }) }
    }
  } catch (e) {
    status.value = e instanceof ApiError && e.status === 405
      ? { kind: 'error', text: t('config.readOnly', { file: name }) }
      : { kind: 'error', text: t('config.saveFailed', { error: errorText(e) }) }
  } finally {
    saving.value = false
  }
}

async function revert() {
  if (!dirty.value) return
  const ok = await confirm({
    title: t('config.revertConfirm', { file: current.value }),
    confirmLabel: t('config.revertConfirmLabel'),
    destructive: true,
  })
  if (ok) {
    text.value = original.value
    status.value = null
  }
}

async function reload() {
  if (dirty.value) {
    const ok = await confirm({
      title: t('config.revertConfirm', { file: current.value }),
      confirmLabel: t('config.revertConfirmLabel'),
      destructive: true,
    })
    if (!ok) return
  }
  await Promise.all([loadFile(current.value), refreshData(true)])
}

function onAdded(latest: ConfigProblem[]) {
  problems.value = latest
  refreshData(true)
}
</script>

<template>
  <div class="mx-auto max-w-[1600px] p-4 sm:p-6 space-y-5">
    <AppHeader />

    <div class="flex items-center gap-3">
      <Button
        variant="ghost"
        size="icon"
        :aria-label="$t('common.back')"
        @click="current ? router.push({ name: 'tools-config' }) : router.push({ name: 'dashboard', params: { tab: 'tools' } })"
      >
        <ArrowLeft class="h-5 w-5" />
      </Button>
      <h1 class="text-2xl font-bold tracking-tight truncate">
        {{ $t('config.title') }}<span v-if="current" class="text-foreground/60 font-normal"> / {{ current }}</span>
      </h1>
    </div>

    <p v-if="listError" class="text-sm text-destructive">{{ listError }}</p>

    <!-- No file picked: the list, as cards (this is the phone's main view). -->
    <div v-if="!current" class="space-y-6">
      <p class="text-sm text-foreground/75">{{ $t('config.intro') }}</p>
      <section v-for="group in [{ key: 'files', list: realFiles }, { key: 'templates', list: templateFiles }]" :key="group.key" class="space-y-2">
        <h2 class="text-sm font-semibold text-muted-foreground uppercase tracking-wider">{{ $t(`config.${group.key}`) }}</h2>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          <button
            v-for="name in group.list"
            :key="name"
            type="button"
            class="flex items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 text-left hover:bg-muted transition-colors"
            @click="open(name)"
          >
            <Lock v-if="group.key === 'templates'" class="h-4 w-4 shrink-0 text-foreground/50" />
            <Braces v-else class="h-4 w-4 shrink-0 text-accent" />
            <span class="min-w-0 flex-1">
              <span class="block font-mono text-sm font-semibold truncate">{{ name }}</span>
              <span class="block text-xs text-foreground/65 truncate">{{ describe(name) }}</span>
            </span>
            <ChevronRight class="h-4 w-4 shrink-0 text-foreground/40" />
          </button>
        </div>
      </section>
    </div>

    <div v-else class="lg:grid lg:grid-cols-[15rem_1fr] lg:gap-6 space-y-4 lg:space-y-0">
      <!-- File switcher: a list beside the editor on wide screens, a select on phones. -->
      <nav class="hidden lg:block space-y-4">
        <div v-for="group in [{ key: 'files', list: realFiles }, { key: 'templates', list: templateFiles }]" :key="group.key" class="space-y-1">
          <h2 class="px-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">{{ $t(`config.${group.key}`) }}</h2>
          <button
            v-for="name in group.list"
            :key="name"
            type="button"
            class="w-full rounded-md px-2 py-1.5 text-left font-mono text-sm truncate transition-colors"
            :class="name === current ? 'bg-accent/15 text-foreground font-semibold' : 'text-foreground/75 hover:bg-muted'"
            @click="open(name)"
          >{{ name }}</button>
        </div>
      </nav>
      <select
        class="lg:hidden w-full rounded-lg border border-border bg-card px-3 py-2.5 text-base font-mono"
        :aria-label="$t('config.pickFile')"
        :value="current"
        @change="open(($event.target as HTMLSelectElement).value)"
      >
        <optgroup :label="$t('config.files')">
          <option v-for="name in realFiles" :key="name" :value="name">{{ name }}</option>
        </optgroup>
        <optgroup :label="$t('config.templates')">
          <option v-for="name in templateFiles" :key="name" :value="name">{{ name }}</option>
        </optgroup>
      </select>

      <div class="min-w-0 space-y-4">
        <p class="text-sm text-foreground/70">
          {{ describe(current) }}
          <span v-if="readOnly" class="ml-1 inline-flex items-center gap-1 rounded bg-muted px-1.5 py-0.5 text-xs">
            <Lock class="h-3 w-3" /> {{ $t('config.templateBadge') }}
          </span>
        </p>

        <p v-if="loading" class="text-sm text-muted-foreground py-6 text-center">{{ $t('config.loading') }}</p>
        <p v-else-if="loadError" class="text-sm text-destructive">{{ loadError }}</p>

        <template v-else>
          <!-- Template: pick entries to copy, or read the file. -->
          <template v-if="readOnly">
            <div class="flex items-center gap-1 border-b border-border">
              <button
                v-for="tab in TEMPLATE_TABS"
                :key="tab"
                class="first:pl-0 px-3 py-1.5 text-base transition-colors border-b-2 -mb-px whitespace-nowrap"
                :class="templateTab === tab ? 'border-accent text-foreground font-semibold' : 'border-transparent text-muted-foreground hover:text-foreground'"
                @click="templateTab = tab"
              >{{ tab === 'entries' ? $t('config.tabEntries') : $t('config.tabText') }}</button>
            </div>
            <TemplateAdder
              v-if="templateTab === 'entries' && templateTarget(current)"
              :template-name="current"
              :template-text="text"
              @added="onAdded"
            />
            <CodeEditor
              v-else
              v-model="text"
              readonly
              :file="current"
              :data="configData"
              :label="current"
              class="h-[65vh]"
            />
          </template>

          <!-- A real file: edit and save. -->
          <template v-else>
            <div class="flex flex-wrap items-center gap-2">
              <Button size="sm" :disabled="saving || !dirty" @click="save">
                <Loader2 v-if="saving" class="h-4 w-4 animate-spin" />
                <Save v-else class="h-4 w-4" />
                {{ saving ? $t('config.saving') : $t('config.save') }}
              </Button>
              <Button variant="outline" size="sm" :disabled="!dirty" @click="revert">
                <RotateCcw class="h-4 w-4" />
                {{ $t('config.revert') }}
              </Button>
              <Button variant="ghost" size="sm" @click="reload">{{ $t('config.reload') }}</Button>
              <span v-if="dirty" class="text-xs font-medium text-accent">{{ $t('config.unsaved') }}</span>
              <span class="hidden sm:inline ml-auto text-xs text-foreground/50">{{ $t('config.shortcutHint') }}</span>
            </div>

            <div
              class="flex flex-wrap items-start gap-x-2 gap-y-1 text-sm"
              :class="check.ok ? 'text-foreground/60' : 'text-destructive'"
            >
              <CheckCircle2 v-if="check.ok" class="h-4 w-4 shrink-0 mt-0.5 text-primary" />
              <XCircle v-else class="h-4 w-4 shrink-0 mt-0.5" />
              <span class="min-w-0 break-words">{{ checkText }}</span>
              <button
                v-if="!check.ok && check.offset !== undefined"
                type="button"
                class="shrink-0 underline hover:no-underline"
                @click="goToError"
              >{{ $t('config.goToError') }}</button>
              <template v-if="check.ok && inlineText">
                <span class="text-foreground/30">·</span>
                <span :class="inlineCounts.error ? 'text-destructive' : inlineCounts.warning ? 'text-amber-500' : ''" :title="$t('config.inlineHint')">{{ inlineText }}</span>
                <button type="button" class="shrink-0 underline hover:no-underline" @click="editor?.nextProblem()">{{ $t('config.inlineShow') }}</button>
              </template>
            </div>

            <CodeEditor
              ref="editor"
              v-model="text"
              :file="current"
              :data="configData"
              :label="current"
              class="h-[65vh] min-h-[18rem]"
              @save="save"
              @diagnostics="inline = $event"
            />
          </template>

          <p
            v-if="status"
            class="rounded-lg border px-3 py-2 text-sm"
            :class="{
              'border-primary/40 bg-primary/10': status.kind === 'ok',
              'border-amber-500/40 bg-amber-500/10': status.kind === 'warn',
              'border-destructive/40 bg-destructive/10 text-destructive': status.kind === 'error',
            }"
          >{{ status.text }}</p>

          <section class="space-y-2">
            <h2 class="text-sm font-semibold text-muted-foreground uppercase tracking-wider">
              {{ $t('config.problems') }}<span v-if="problems.length" class="normal-case tracking-normal font-normal"> · {{ problemSummary }}</span>
            </h2>
            <p v-if="!problems.length" class="flex items-center gap-2 text-sm text-foreground/70">
              <CheckCircle2 class="h-4 w-4 text-primary" /> {{ $t('config.noProblems') }}
            </p>
            <template v-else>
              <p class="text-xs text-foreground/55">{{ $t('config.problemsHint') }}</p>
              <ul
                class="rounded-lg border divide-y divide-border text-sm"
                :class="problemCounts.error ? 'border-destructive/30' : 'border-amber-500/30'"
              >
                <li v-for="(p, i) in problems" :key="i" class="flex items-start gap-2 px-3 py-2" :data-severity="p.severity">
                  <XCircle v-if="p.severity === 'error'" class="h-4 w-4 shrink-0 mt-0.5 text-destructive" />
                  <TriangleAlert v-else class="h-4 w-4 shrink-0 mt-0.5 text-amber-500" />
                  <span class="min-w-0 break-words">
                    <span
                      class="mr-1.5 rounded px-1.5 py-0.5 text-xs font-semibold"
                      :class="p.severity === 'error' ? 'bg-destructive/15 text-destructive' : 'bg-amber-500/15 text-amber-500'"
                    >{{ p.severity === 'error' ? $t('config.problemError') : $t('config.problemWarning') }}</span>{{ p.message }}
                  </span>
                </li>
              </ul>
            </template>
          </section>
        </template>
      </div>
    </div>
  </div>
</template>
