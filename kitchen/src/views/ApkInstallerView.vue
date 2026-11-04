<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { uploadApk, getApkStatus, getApkCapability, openApkInstallSettings, ApiError } from '@/api/client'
import Button from '@/components/ui/Button.vue'
import Progress from '@/components/ui/Progress.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import { ArrowLeft, PackagePlus, CheckCircle2, XCircle, Smartphone, ShieldAlert } from 'lucide-vue-next'

type Phase = 'idle' | 'uploading' | 'waiting' | 'success' | 'failure'

const router = useRouter()
const { t } = useI18n()
const phase = ref<Phase>('idle')
const progress = ref(0)
const error = ref('')
const fileName = ref('')
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement>()
let pollTimer: ReturnType<typeof setInterval> | undefined
let abortUpload: (() => void) | null = null
let uploadCancelled = false

/** null while unknown (an older server, or the check failed): the upload then tries anyway. */
const canInstall = ref<boolean | null>(null)
const settingsOpened = ref(false)
const settingsError = ref(false)

async function checkPermission() {
  try {
    canInstall.value = (await getApkCapability()).canInstall
  } catch {
    canInstall.value = null
  }
}

async function openSettings() {
  settingsError.value = false
  settingsOpened.value = await openApkInstallSettings()
  settingsError.value = !settingsOpened.value
}

onMounted(checkPermission)

onUnmounted(() => {
  clearInterval(pollTimer)
  uploadCancelled = true
  abortUpload?.()
})

function onDrop(e: DragEvent) {
  dragOver.value = false
  const file = e.dataTransfer?.files[0]
  if (file) install(file)
}

function onPick(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (file) install(file)
}

async function install(file: File) {
  if (!file.name.toLowerCase().endsWith('.apk')) {
    error.value = t('tools.onlyApkFiles')
    phase.value = 'failure'
    return
  }
  fileName.value = file.name
  phase.value = 'uploading'
  progress.value = 0
  error.value = ''
  uploadCancelled = false
  try {
    const { promise, abort } = uploadApk(file, pct => (progress.value = pct))
    abortUpload = abort
    const { installId } = await promise
    abortUpload = null
    phase.value = 'waiting'
    poll(installId)
  } catch (e) {
    abortUpload = null
    if (!uploadCancelled) {
      // A refused permission can also show up as a dropped connection, since the server answers
      // before reading the upload. Either way, re-check so the page can say what to do.
      await checkPermission()
      if (canInstall.value === false || (e instanceof ApiError && e.status === 403)) {
        canInstall.value = false
        reset()
        return
      }
      error.value = e instanceof ApiError && e.status !== 0 ? `${t('tools.uploadFailed')} ${e.message}` : t('tools.uploadFailed')
      phase.value = 'failure'
    }
  }
}

function cancelUpload() {
  uploadCancelled = true
  abortUpload?.()
  reset()
}

function poll(installId: string) {
  pollTimer = setInterval(async () => {
    try {
      const s = await getApkStatus(installId)
      if (s.status === 'success') {
        clearInterval(pollTimer)
        phase.value = 'success'
      } else if (s.status === 'failure') {
        clearInterval(pollTimer)
        error.value = s.message ?? t('tools.installFailed')
        phase.value = 'failure'
      }
    } catch (e) {
      // The device forgets installs when Cannoli restarts, so the result can't be known any more.
      if (e instanceof ApiError && e.status === 404) {
        clearInterval(pollTimer)
        error.value = t('tools.installStatusLost')
        phase.value = 'failure'
      }
    }
  }, 1500)
}

function reset() {
  clearInterval(pollTimer)
  phase.value = 'idle'
  progress.value = 0
  error.value = ''
  fileName.value = ''
}
</script>

<template>
  <div class="mx-auto max-w-[1600px] p-4 sm:p-6 space-y-6">
    <AppHeader />
    <div class="flex items-center gap-3">
      <Button variant="ghost" size="icon" @click="router.push({ name: 'dashboard', params: { tab: 'tools' } })">
        <ArrowLeft class="h-5 w-5" />
      </Button>
      <h1 class="text-2xl font-bold tracking-tight">{{ $t('tools.apkInstaller') }}</h1>
    </div>

    <div
      v-if="canInstall === false"
      class="rounded-xl border border-amber-500/50 bg-amber-500/10 p-4 sm:p-5 space-y-3"
    >
      <div class="flex items-start gap-3">
        <ShieldAlert class="h-5 w-5 shrink-0 text-amber-500 mt-0.5" />
        <div class="space-y-1.5 text-sm">
          <p class="font-semibold">{{ $t('tools.installPermissionTitle') }}</p>
          <p class="text-foreground/80">{{ $t('tools.installPermissionBody') }}</p>
          <p class="text-foreground/80">{{ $t('tools.installPermissionSteps') }}</p>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <Button size="sm" @click="openSettings">{{ $t('tools.installPermissionOpen') }}</Button>
        <Button size="sm" variant="outline" @click="checkPermission">{{ $t('tools.installPermissionRecheck') }}</Button>
      </div>
      <p v-if="settingsOpened" class="text-sm text-foreground/80">{{ $t('tools.installPermissionOpened') }}</p>
      <p v-if="settingsError" class="text-sm text-destructive">{{ $t('tools.installPermissionOpenFailed') }}</p>
    </div>

    <div
      v-if="phase === 'idle'"
      class="rounded-xl border-2 border-dashed p-8 sm:p-12 text-center transition-colors cursor-pointer"
      :class="dragOver ? 'border-accent bg-accent/5' : 'border-border hover:border-accent/50'"
      @dragover.prevent="dragOver = true"
      @dragleave="dragOver = false"
      @drop.prevent="onDrop"
      @click="fileInput?.click()"
    >
      <PackagePlus class="mx-auto h-10 w-10 text-muted-foreground" />
      <p class="mt-4 text-sm font-medium">{{ $t('tools.dropApk') }}</p>
      <p class="mt-1 text-sm text-muted-foreground">{{ $t('tools.confirmOnDevice') }}</p>
      <input ref="fileInput" type="file" accept=".apk" class="hidden" @change="onPick" />
    </div>

    <div v-else class="rounded-xl border border-border bg-card p-8 space-y-4 text-center">
      <template v-if="phase === 'uploading'">
        <p class="text-sm font-medium">{{ $t('tools.uploadingFile', { file: fileName }) }}</p>
        <Progress :value="progress" />
        <Button variant="outline" @click="cancelUpload">{{ $t('common.cancel') }}</Button>
      </template>

      <template v-else-if="phase === 'waiting'">
        <Smartphone class="mx-auto h-10 w-10 text-accent animate-pulse" />
        <p class="text-sm font-medium">{{ $t('tools.waitingForConfirmation') }}</p>
        <p class="text-sm text-muted-foreground">{{ $t('tools.confirmInstallPrompt') }}</p>
        <p class="text-xs text-muted-foreground">{{ $t('tools.noPromptHint') }}</p>
        <Button variant="outline" @click="reset">{{ $t('tools.stopWaiting') }}</Button>
      </template>

      <template v-else-if="phase === 'success'">
        <CheckCircle2 class="mx-auto h-10 w-10 text-green-500" />
        <p class="text-sm font-medium">{{ $t('tools.fileInstalled', { file: fileName }) }}</p>
        <Button variant="outline" @click="reset">{{ $t('tools.installAnother') }}</Button>
      </template>

      <template v-else>
        <XCircle class="mx-auto h-10 w-10 text-destructive" />
        <p class="text-sm font-medium">{{ $t('tools.installFailed') }}</p>
        <p class="text-sm text-muted-foreground">{{ error }}</p>
        <Button variant="outline" @click="reset">{{ $t('tools.tryAgain') }}</Button>
      </template>
    </div>
  </div>
</template>
