<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { listControllerMappings, downloadControllerMapping, type ControllerMapping } from '@/api/client'
import Button from '@/components/ui/Button.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import { ArrowLeft, Gamepad2, Download } from 'lucide-vue-next'

const router = useRouter()
const { t } = useI18n()
const mappings = ref<ControllerMapping[]>([])
const loading = ref(true)
const error = ref('')
const busy = ref('')

onMounted(async () => {
  try {
    mappings.value = await listControllerMappings()
  } catch {
    error.value = t('tools.mappingsLoadFailed')
  } finally {
    loading.value = false
  }
})

async function onDownload(m: ControllerMapping) {
  busy.value = m.file
  try {
    await downloadControllerMapping(m.file)
  } catch {
    error.value = t('tools.mappingsDownloadFailed')
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="mx-auto max-w-[1600px] p-6 space-y-6">
    <AppHeader />
    <div class="flex items-center gap-3">
      <Button variant="ghost" size="icon" @click="router.push({ name: 'dashboard', params: { tab: 'tools' } })">
        <ArrowLeft class="h-5 w-5" />
      </Button>
      <h1 class="text-2xl font-bold tracking-tight">{{ $t('tools.controllerMappings') }}</h1>
    </div>

    <p v-if="error" class="text-base text-destructive">{{ error }}</p>
    <p v-if="loading" class="text-base text-foreground/75 py-8 text-center">{{ $t('common.loading') }}</p>
    <p v-else-if="!mappings.length" class="text-base text-foreground/75 py-8 text-center">
      {{ $t('tools.noMappings') }}
    </p>

    <div v-else class="space-y-2">
      <div
        v-for="m in mappings"
        :key="m.file"
        class="flex items-center gap-3 px-4 py-3 rounded-lg border border-border bg-card"
      >
        <div class="flex items-center justify-center h-9 w-9 rounded-lg bg-accent/15 shrink-0">
          <Gamepad2 class="h-4 w-4 text-accent" />
        </div>
        <div class="flex-1 min-w-0">
          <div class="text-base font-semibold text-foreground truncate">{{ m.name }}</div>
          <div class="text-sm text-foreground/60 truncate">{{ m.file }}</div>
        </div>
        <div class="flex gap-1.5 shrink-0">
          <button
            class="p-2 rounded text-foreground/70 hover:bg-muted hover:text-foreground disabled:opacity-40"
            :disabled="busy === m.file"
            :title="$t('common.download')"
            @click="onDownload(m)"
          >
            <Download class="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
