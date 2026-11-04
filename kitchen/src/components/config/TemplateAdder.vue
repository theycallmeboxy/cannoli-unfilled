<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getConfigFile, putConfigFile, type ConfigProblem } from '@/api/client'
import {
  missingEmulators,
  mergeEntries,
  parseObject,
  placeInDirectory,
  templateEntries,
  templateTarget,
  type JsonObject,
  type MenuKind,
} from '@/lib/configMerge'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'
import { Search, ListPlus, Loader2, CheckCircle2, TriangleAlert } from 'lucide-vue-next'

/**
 * Lists a template's entries and copies the chosen ones into the real file, never replacing an
 * entry the file already has. Systems can bring the emulators they use along, and new systems
 * and shortcuts can be placed at the end of the main menu.
 */
const props = defineProps<{ templateName: string; templateText: string }>()
const emit = defineEmits<{ added: [problems: ConfigProblem[]] }>()
const { t } = useI18n()

const EMULATORS = 'emulators.json'
const EMULATORS_TEMPLATE = 'emulators.template.json'
const DIRECTORY = 'directory.json'

const target = computed(() => templateTarget(props.templateName) ?? '')
const isSystems = computed(() => target.value === 'systems.json')
const menuKind = computed<MenuKind | null>(() =>
  target.value === 'systems.json' ? 'system' : target.value === 'shortcuts.json' ? 'shortcut' : null,
)

const template = computed<JsonObject>(() => {
  try {
    return parseObject(props.templateText)
  } catch {
    return {}
  }
})
const entries = computed(() => templateEntries(template.value))

const search = ref('')
const selected = reactive(new Set<string>())
const existing = ref<Set<string>>(new Set())
const loadError = ref('')
const emulatorsText = ref('')
const emulatorsTemplate = ref<JsonObject>({})
/** Emulators the user unticked; everything else the selection needs is added. */
const skippedEmulators = reactive(new Set<string>())
const placeOnMenu = ref(true)
const busy = ref(false)

interface Report {
  lines: { ok: boolean; text: string }[]
  problems: ConfigProblem[]
}
const report = ref<Report | null>(null)

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return entries.value
  return entries.value.filter(e =>
    e.key.toLowerCase().includes(q) || e.name.toLowerCase().includes(q) || e.note.toLowerCase().includes(q),
  )
})

const selectable = computed(() => filtered.value.filter(e => !existing.value.has(e.key)))

const neededEmulators = computed(() => {
  if (!isSystems.value || !selected.size) return { inTemplate: [] as string[], unknown: [] as string[] }
  try {
    return missingEmulators(template.value, [...selected], emulatorsText.value, emulatorsTemplate.value)
  } catch {
    return { inTemplate: [], unknown: [] }
  }
})

async function load() {
  loadError.value = ''
  try {
    const file = await getConfigFile(target.value)
    existing.value = new Set(Object.keys(parseObject(file.text)))
    for (const key of [...selected]) if (existing.value.has(key)) selected.delete(key)
  } catch (e) {
    loadError.value = t('config.targetUnreadable', { file: target.value, error: errorText(e) })
  }
  if (isSystems.value) {
    try {
      const [emulators, emulatorsTpl] = await Promise.all([getConfigFile(EMULATORS), getConfigFile(EMULATORS_TEMPLATE)])
      emulatorsText.value = emulators.text
      emulatorsTemplate.value = parseObject(emulatorsTpl.text)
    } catch (e) {
      loadError.value = t('config.targetUnreadable', { file: EMULATORS, error: errorText(e) })
    }
  }
}

onMounted(load)
watch(() => props.templateName, () => {
  selected.clear()
  skippedEmulators.clear()
  report.value = null
  search.value = ''
  load()
})

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function toggle(key: string) {
  if (selected.has(key)) selected.delete(key)
  else selected.add(key)
}

function selectShown() {
  for (const e of selectable.value) selected.add(e.key)
}

function toggleEmulator(key: string) {
  if (skippedEmulators.has(key)) skippedEmulators.delete(key)
  else skippedEmulators.add(key)
}

function keyList(keys: string[]): string {
  return keys.join(', ')
}

/** Reads a file fresh, so a change made elsewhere since this page loaded isn't lost. */
async function readFresh(name: string): Promise<string> {
  return (await getConfigFile(name)).text
}

async function save(name: string, text: string): Promise<ConfigProblem[]> {
  const result = await putConfigFile(name, text)
  if (!result.saved) throw new Error(t('config.refused', { file: name }))
  return result.problems
}

async function add() {
  if (!selected.size || busy.value) return
  busy.value = true
  const lines: Report['lines'] = []
  let problems: ConfigProblem[] = []
  const keys = entries.value.map(e => e.key).filter(k => selected.has(k))
  try {
    // Emulators first, so the systems that use them find them when they are checked.
    const emulatorKeys = neededEmulators.value.inTemplate.filter(k => !skippedEmulators.has(k))
    if (emulatorKeys.length) {
      const merged = mergeEntries(await readFresh(EMULATORS), emulatorsTemplate.value, emulatorKeys)
      if (merged.added.length) problems = await save(EMULATORS, merged.text)
      if (merged.added.length) lines.push({ ok: true, text: t('config.addedTo', { file: EMULATORS, keys: keyList(merged.added) }) })
      if (merged.skipped.length) lines.push({ ok: true, text: t('config.alreadyIn', { file: EMULATORS, keys: keyList(merged.skipped) }) })
    }

    const merged = mergeEntries(await readFresh(target.value), template.value, keys)
    if (merged.added.length) problems = await save(target.value, merged.text)
    if (merged.added.length) lines.push({ ok: true, text: t('config.addedTo', { file: target.value, keys: keyList(merged.added) }) })
    if (merged.skipped.length) lines.push({ ok: false, text: t('config.skippedExisting', { file: target.value, keys: keyList(merged.skipped) }) })
    if (merged.missing.length) lines.push({ ok: false, text: t('config.notInTemplate', { keys: keyList(merged.missing) }) })
    if (neededEmulators.value.unknown.length) {
      lines.push({ ok: false, text: t('config.unknownEmulators', { keys: keyList(neededEmulators.value.unknown) }) })
    }

    if (placeOnMenu.value && menuKind.value && merged.added.length) {
      const placed = placeInDirectory(await readFresh(DIRECTORY), menuKind.value, merged.added)
      if (placed.added.length) {
        problems = await save(DIRECTORY, placed.text)
        lines.push({ ok: true, text: t('config.placedOnMenu', { keys: keyList(placed.added) }) })
      }
    }
    selected.clear()
  } catch (e) {
    lines.push({ ok: false, text: errorText(e) })
  } finally {
    report.value = { lines, problems }
    busy.value = false
    await load()
    emit('added', problems)
  }
}
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm text-foreground/80">{{ $t('config.adderIntro', { file: target }) }}</p>
    <p v-if="loadError" class="text-sm text-destructive">{{ loadError }}</p>

    <div class="flex flex-wrap items-center gap-2">
      <div class="relative flex-1 min-w-[12rem]">
        <Search class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
        <Input v-model="search" :placeholder="$t('config.searchEntries')" class="!pl-9 !h-9 !rounded-lg" />
      </div>
      <Button variant="outline" size="sm" :disabled="!selectable.length" @click="selectShown">{{ $t('config.selectShown') }}</Button>
      <Button variant="ghost" size="sm" :disabled="!selected.size" @click="selected.clear()">{{ $t('config.selectNone') }}</Button>
    </div>

    <div class="rounded-lg border border-border divide-y divide-border max-h-[55vh] overflow-y-auto">
      <p v-if="!filtered.length" class="p-4 text-sm text-muted-foreground text-center">{{ $t('config.noEntries') }}</p>
      <label
        v-for="entry in filtered"
        :key="entry.key"
        class="flex items-start gap-3 px-3 py-2.5"
        :class="existing.has(entry.key) ? 'opacity-55' : 'cursor-pointer hover:bg-muted/50'"
      >
        <input
          type="checkbox"
          class="mt-1 h-4 w-4 shrink-0 accent-accent"
          :checked="selected.has(entry.key) || existing.has(entry.key)"
          :disabled="existing.has(entry.key)"
          @change="toggle(entry.key)"
        />
        <span class="min-w-0 flex-1">
          <span class="flex flex-wrap items-baseline gap-x-2">
            <span class="font-semibold text-sm">{{ entry.name }}</span>
            <code class="text-xs text-foreground/60">{{ entry.key }}</code>
            <span v-if="existing.has(entry.key)" class="text-xs text-foreground/60">{{ $t('config.alreadyThere', { file: target }) }}</span>
          </span>
          <span v-if="entry.note" class="block text-xs text-foreground/65 mt-0.5">{{ entry.note }}</span>
          <span v-if="isSystems && entry.emulators.length" class="block text-xs text-foreground/50 mt-0.5 font-mono truncate">
            {{ entry.emulators.join(' · ') }}
          </span>
        </span>
      </label>
    </div>

    <div v-if="isSystems && (neededEmulators.inTemplate.length || neededEmulators.unknown.length)" class="rounded-lg border border-border bg-card p-3 space-y-2">
      <p class="text-sm font-medium">{{ $t('config.emulatorsNeeded') }}</p>
      <div class="flex flex-wrap gap-x-4 gap-y-1.5">
        <label v-for="key in neededEmulators.inTemplate" :key="key" class="flex items-center gap-2 text-sm cursor-pointer">
          <input type="checkbox" class="h-4 w-4 accent-accent" :checked="!skippedEmulators.has(key)" @change="toggleEmulator(key)" />
          <code class="text-xs">{{ key }}</code>
        </label>
      </div>
      <p v-if="neededEmulators.unknown.length" class="text-xs text-amber-500">
        {{ $t('config.unknownEmulators', { keys: neededEmulators.unknown.join(', ') }) }}
      </p>
    </div>

    <label v-if="menuKind" class="flex items-start gap-2 text-sm cursor-pointer">
      <input v-model="placeOnMenu" type="checkbox" class="mt-0.5 h-4 w-4 accent-accent" />
      <span>{{ menuKind === 'system' ? $t('config.placeSystems') : $t('config.placeShortcuts') }}</span>
    </label>

    <div class="sticky bottom-0 -mx-1 px-1 py-2 bg-background/95 backdrop-blur flex items-center gap-3">
      <Button :disabled="!selected.size || busy" @click="add">
        <Loader2 v-if="busy" class="h-4 w-4 animate-spin" />
        <ListPlus v-else class="h-4 w-4" />
        {{ $t('config.addSelected', { n: selected.size, file: target }, selected.size) }}
      </Button>
    </div>

    <div v-if="report" class="rounded-lg border border-border bg-card p-3 space-y-1.5 text-sm">
      <p v-for="(line, i) in report.lines" :key="i" class="flex items-start gap-2">
        <CheckCircle2 v-if="line.ok" class="h-4 w-4 shrink-0 text-primary mt-0.5" />
        <TriangleAlert v-else class="h-4 w-4 shrink-0 text-amber-500 mt-0.5" />
        <span>{{ line.text }}</span>
      </p>
      <p v-if="!report.lines.length" class="text-foreground/70">{{ $t('config.nothingAdded') }}</p>
    </div>
  </div>
</template>
