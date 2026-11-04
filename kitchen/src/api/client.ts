import { ref } from 'vue'

export interface FileEntry {
  name: string
  type: 'file' | 'dir'
  size: number
}

export interface ListResponse {
  path: string
  entries: FileEntry[]
}

const token = ref('')
const baseUrl = ref('')
const authRequired = ref<boolean | null>(null)

let onUnauthorized: (() => void) | null = null
let onConnectionError: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export function setConnectionErrorHandler(handler: () => void) {
  onConnectionError = handler
}

export const DEFAULT_PORT = '1091'

/** Strip any scheme/trailing slash, ensure a port, and return an http base URL. */
function normalizeBaseUrl(host: string): string {
  const bare = host.replace(/^https?:\/\//, '').replace(/\/$/, '')
  const withPort = bare.includes(':') ? bare : `${bare}:${DEFAULT_PORT}`
  return `http://${withPort}`
}

export function setCredentials(host: string, pin: string) {
  baseUrl.value = normalizeBaseUrl(host)
  token.value = pin ? btoa(`nonna:${pin}`) : ''
  localStorage.setItem('cannoli_host', host)
  localStorage.removeItem('cannoli_pinless')
  if (pin) localStorage.setItem('cannoli_pin', pin)
  else localStorage.removeItem('cannoli_pin')
}

export function setBaseUrlOnly(host: string) {
  baseUrl.value = normalizeBaseUrl(host)
  token.value = ''
  authRequired.value = false
  localStorage.setItem('cannoli_host', host)
  localStorage.setItem('cannoli_pinless', '1')
  localStorage.removeItem('cannoli_pin')
}

export function restoreCredentials(): boolean {
  const host = localStorage.getItem('cannoli_host')
  const pin = localStorage.getItem('cannoli_pin')
  if (host && pin) {
    setCredentials(host, pin)
    return true
  }
  if (host && localStorage.getItem('cannoli_pinless')) {
    setBaseUrlOnly(host)
    return true
  }
  return false
}

export interface AuthStatus {
  required: boolean
}

export async function getAuthStatus(host?: string): Promise<AuthStatus> {
  const target = host ? normalizeBaseUrl(host) : baseUrl.value
  if (!target) throw new Error('ConnectionError')
  const res = await fetch(`${target}/api/auth`, { method: 'GET' })
  if (!res.ok) throw new Error('ConnectionError')
  const status = (await res.json()) as AuthStatus
  authRequired.value = status.required
  return status
}

export function getBaseUrl() {
  return baseUrl.value
}

export function isAuthenticated() {
  if (authRequired.value === false) return true
  return token.value !== ''
}

export function clearCredentials() {
  token.value = ''
  baseUrl.value = ''
  authRequired.value = null
  localStorage.removeItem('cannoli_pin')
  localStorage.removeItem('cannoli_pinless')
}

function buildPath(resource: string, ...segments: (string | undefined)[]): string {
  const encoded = [resource, ...segments.filter(Boolean)].map(p => encodeURIComponent(p!))
  return `/api/${encoded.join('/')}`
}

async function request(path: string, init?: RequestInit): Promise<Response> {
  let res: Response
  try {
    const headers: Record<string, string> = { ...(init?.headers as Record<string, string> | undefined) }
    if (token.value) headers.Authorization = `Basic ${token.value}`
    res = await fetch(`${baseUrl.value}${path}`, {
      ...init,
      headers,
    })
  } catch {
    clearCredentials()
    onConnectionError?.()
    throw new Error('ConnectionError')
  }
  if (res.status === 401) {
    clearCredentials()
    onUnauthorized?.()
    throw new Error('Unauthorized')
  }
  return res
}

export async function getInfo() {
  const res = await request('/api/info')
  return res.json()
}

export interface TagsResult {
  tags: string[]
  /** Manufacturer per tag, as the launcher defines it in platforms.json. Empty on a launcher that
   *  predates the field, which leaves grouping on the built-in fallback. */
  groups: Record<string, string>
}

export async function getTags(): Promise<TagsResult> {
  const res = await request('/api/tags')
  const data = await res.json()
  return { tags: data.tags, groups: data.groups ?? {} }
}

export interface GameRow {
  id: number
  rom: string
  displayName: string
  sortKey: string
  path: string
  folder: string
  hasArt: boolean
  artUrl?: string
  savesCount: number
  guidesCount: number
  lastPlayedAt?: number
  multiDisc: boolean
}

export interface PlatformGames {
  platform: string
  displayName: string
  folders: string[]
  games: GameRow[]
  /** Width over height this platform's covers share, measured server-side. Null when unmeasurable. */
  artAspect?: number | null
}

export interface GameDetail extends GameRow {
  platform: string
  platformDisplayName: string
  /** Detail-only. The list omits these because nothing renders them there and each one costs a
   *  filesystem stat per game, which does not scale to a large library. */
  size: number
  modified: number
}

export async function getGames(tag: string): Promise<PlatformGames> {
  const res = await request(`/api/games/${encodeURIComponent(tag)}`)
  if (!res.ok) throw new Error(`getGames ${res.status}`)
  return res.json()
}

export async function getGame(tag: string, id: number): Promise<GameDetail> {
  const res = await request(`/api/games/${encodeURIComponent(tag)}/${id}`)
  if (!res.ok) throw new Error(`getGame ${res.status}`)
  return res.json()
}

function gameBase(tag: string, id: number): string {
  return `/api/games/${encodeURIComponent(tag)}/${id}`
}

export interface GameFile {
  name: string
  size: number
  modified: number
}

export interface GameRomFile {
  name: string
  path: string
  size: number
  modified: number
}

export async function gameArtBlob(tag: string, id: number): Promise<string | null> {
  const res = await request(`${gameBase(tag, id)}/art`)
  if (!res.ok) return null
  return URL.createObjectURL(await res.blob())
}

/** Fetch an already-resolved, percent-encoded api path as a blob. Caller must revoke the url.
 *  Game lists carry artUrl so covers skip the per-game art lookup, which costs a full directory
 *  listing on the device and does not scale past a few hundred games. */
export async function apiPathBlob(path: string): Promise<string | null> {
  const res = await request(path)
  if (!res.ok) return null
  return URL.createObjectURL(await res.blob())
}

export async function resourceFileBlob(resource: string, tag: string, name: string): Promise<string | null> {
  const res = await request(buildPath(resource, tag, name))
  if (!res.ok) return null
  return URL.createObjectURL(await res.blob())
}

/** Fetch any browse-resource file as an authenticated blob. Caller must revoke the url. */
export async function fileBlob(resource: string, ...segments: string[]): Promise<{ url: string; type: string }> {
  const res = await request(buildPath(resource, ...segments))
  if (!res.ok) throw new Error(`fileBlob ${res.status}`)
  const blob = await res.blob()
  return { url: URL.createObjectURL(blob), type: blob.type }
}

export async function uploadGameArt(tag: string, id: number, file: File): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/art?name=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    body: file,
  })
  if (!res.ok) throw new Error(`uploadGameArt ${res.status}`)
}

export async function deleteGameArt(tag: string, id: number): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/art`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`deleteGameArt ${res.status}`)
}

export async function listGameRoms(tag: string, id: number): Promise<GameRomFile[]> {
  const res = await request(`${gameBase(tag, id)}/roms`)
  if (!res.ok) throw new Error(`listGameRoms ${res.status}`)
  return (await res.json()).files
}

export async function downloadGameRomFile(tag: string, id: number, path: string, filename: string): Promise<void> {
  await downloadFile(`${gameBase(tag, id)}/rom?file=${encodeURIComponent(path)}`, filename)
}

export async function deleteGame(tag: string, id: number, purge: boolean): Promise<void> {
  const res = await request(`${gameBase(tag, id)}${purge ? '?purge=true' : ''}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`deleteGame ${res.status}`)
}

export async function moveGame(tag: string, id: number, folder: string): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/move?folder=${encodeURIComponent(folder)}`, { method: 'POST' })
  if (!res.ok) throw new Error(`moveGame ${res.status}`)
}

export async function renameGame(tag: string, id: number, name: string): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/rename?name=${encodeURIComponent(name)}`, { method: 'POST' })
  if (!res.ok) throw new Error(`renameGame ${res.status}`)
}

export async function listGameSaves(tag: string, id: number): Promise<GameFile[]> {
  const res = await request(`${gameBase(tag, id)}/saves`)
  if (!res.ok) throw new Error(`listGameSaves ${res.status}`)
  return (await res.json()).files
}

export async function downloadGameSave(tag: string, id: number, name: string): Promise<void> {
  await downloadFile(`${gameBase(tag, id)}/saves?file=${encodeURIComponent(name)}`, name)
}

export async function uploadGameSave(tag: string, id: number, file: File): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/saves?name=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    body: file,
  })
  if (!res.ok) throw new Error(`uploadGameSave ${res.status}`)
}

export async function deleteGameSave(tag: string, id: number, name: string): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/saves?file=${encodeURIComponent(name)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`deleteGameSave ${res.status}`)
}

export async function listGameGuides(tag: string, id: number): Promise<GameFile[]> {
  const res = await request(`${gameBase(tag, id)}/guides`)
  if (!res.ok) throw new Error(`listGameGuides ${res.status}`)
  return (await res.json()).files
}

export async function downloadGameGuide(tag: string, id: number, name: string): Promise<void> {
  await downloadFile(`${gameBase(tag, id)}/guides?file=${encodeURIComponent(name)}`, name)
}

/** Fetch a guide as an authenticated blob. Caller must revoke the returned url. */
export async function gameGuideBlob(tag: string, id: number, name: string): Promise<{ url: string; type: string }> {
  const res = await request(`${gameBase(tag, id)}/guides?file=${encodeURIComponent(name)}`)
  if (!res.ok) throw new Error(`gameGuideBlob ${res.status}`)
  const blob = await res.blob()
  return { url: URL.createObjectURL(blob), type: blob.type }
}

export async function uploadGameGuide(tag: string, id: number, file: File): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/guides?name=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    body: file,
  })
  if (!res.ok) throw new Error(`uploadGameGuide ${res.status}`)
}

export async function deleteGameGuide(tag: string, id: number, name: string): Promise<void> {
  const res = await request(`${gameBase(tag, id)}/guides?file=${encodeURIComponent(name)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`deleteGameGuide ${res.status}`)
}

export async function listFiles(resource: string, ...subpath: (string | undefined)[]): Promise<ListResponse> {
  const path = buildPath(resource, ...subpath)
  const res = await request(path)
  return res.json()
}

export async function listFilesRecursive(resource: string, ...subpath: (string | undefined)[]): Promise<ListResponse> {
  const path = buildPath(resource, ...subpath) + '?recursive=true'
  const res = await request(path)
  return res.json()
}

/** An error carrying the server's status and its `error` message, when it sent one. */
export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function failure(res: Response, what: string): Promise<ApiError> {
  let message = `${what} ${res.status}`
  try {
    const body = await res.json()
    if (body && typeof body.error === 'string') message = body.error
  } catch {
    // not JSON: keep the status
  }
  return new ApiError(res.status, message)
}

export async function createFolder(resource: string, ...subpath: (string | undefined)[]): Promise<{ ok: boolean }> {
  const path = buildPath(resource, ...subpath)
  const res = await request(path, { method: 'PUT' })
  if (!res.ok) throw await failure(res, 'createFolder')
  return res.json()
}

export async function moveFile(resource: string, fromSegments: string[], to: string): Promise<{ ok: boolean }> {
  const path = buildPath(resource, ...fromSegments)
  const res = await request(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ to }),
  })
  if (!res.ok) throw await failure(res, 'moveFile')
  return res.json()
}

export async function downloadToDisk(resource: string, segments: string[], filename: string): Promise<void> {
  await downloadFile(buildPath(resource, ...segments), filename)
}

/**
 * Saves a file through a plain link, so the browser streams it to disk instead of holding all of
 * it in the page's memory (a large ROM on a phone would not fit). A link can't send headers, so
 * the credential rides in the query, and `download` asks the server to send it as an attachment.
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const sep = path.includes('?') ? '&' : '?'
  const params = new URLSearchParams({ download: filename })
  if (token.value) params.set('auth', token.value)
  const url = `${baseUrl.value}${path}${sep}${params.toString()}`
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

export async function deleteFile(resource: string, ...subpath: (string | undefined)[]): Promise<{ ok: boolean }> {
  const path = buildPath(resource, ...subpath)
  const res = await request(path, { method: 'DELETE' })
  if (!res.ok) throw await failure(res, 'deleteFile')
  return res.json()
}

export interface Volume {
  id: string
  label: string
  totalBytes: number
  freeBytes: number
}

export async function listVolumes(): Promise<Volume[]> {
  const res = await request('/api/fs')
  const data = await res.json()
  return data.volumes
}

export interface ApkStatus {
  status: 'pending_user' | 'success' | 'failure'
  message?: string
}

export function uploadApk(
  file: File,
  onProgress?: (pct: number) => void,
): { promise: Promise<{ ok: boolean; installId: string }>; abort: () => void } {
  return uploadFiles<{ ok: boolean; installId: string }>('apk', [], [file], onProgress)
}

export async function getApkStatus(installId: string): Promise<ApkStatus> {
  const res = await request(`/api/apk/${encodeURIComponent(installId)}`)
  if (!res.ok) throw await failure(res, 'getApkStatus')
  return res.json()
}

/** Whether Android lets Cannoli install apps ("Install unknown apps" for this app). */
export async function getApkCapability(): Promise<{ canInstall: boolean }> {
  const res = await request('/api/apk')
  if (!res.ok) throw await failure(res, 'getApkCapability')
  return res.json()
}

/** Opens Cannoli's "Install unknown apps" switch on the device's screen. */
export async function openApkInstallSettings(): Promise<boolean> {
  const res = await request('/api/apk/permission', { method: 'POST' })
  if (!res.ok) return false
  return (await res.json()).ok === true
}

export interface AppsResponse {
  tools: string[]
  ports: string[]
}

export async function getApps(): Promise<AppsResponse> {
  const res = await request('/api/apps')
  if (!res.ok) return { tools: [], ports: [] }
  return res.json()
}

export function uploadFiles<T = { ok: boolean; files: string[] }>(
  resource: string,
  subpath: string[],
  files: File[],
  onProgress?: (pct: number) => void,
): { promise: Promise<T>; abort: () => void } {
  const path = buildPath(resource, ...subpath)

  const formData = new FormData()
  for (const file of files) {
    formData.append('file', file, file.name)
  }

  const xhr = new XMLHttpRequest()

  const promise = new Promise<T>((resolve, reject) => {
    xhr.open('POST', `${baseUrl.value}${path}`)
    if (token.value) xhr.setRequestHeader('Authorization', `Basic ${token.value}`)

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    })

    xhr.addEventListener('load', () => {
      if (xhr.status === 200) {
        resolve(JSON.parse(xhr.responseText))
      } else {
        let message = `Upload failed: ${xhr.status}`
        try {
          const body = JSON.parse(xhr.responseText)
          if (body && typeof body.error === 'string') message = body.error
        } catch {
          // not JSON: keep the status
        }
        reject(new ApiError(xhr.status, message))
      }
    })

    xhr.addEventListener('error', () => reject(new ApiError(0, 'Upload failed')))
    xhr.addEventListener('abort', () => reject(new Error('Upload cancelled')))
    xhr.send(formData)
  })

  return { promise, abort: () => xhr.abort() }
}

export interface ControllerMapping {
  file: string
  name: string
}

export async function listControllerMappings(): Promise<ControllerMapping[]> {
  const res = await request('/api/mappings')
  if (!res.ok) throw new Error(`listControllerMappings ${res.status}`)
  return (await res.json()).mappings
}

export async function downloadControllerMapping(file: string): Promise<void> {
  await downloadFile(`/api/mappings/${encodeURIComponent(file)}`, file)
}

// --- Config files (Config/*.json) ---

/**
 * One problem Cannoli found in the config files. An error is something it skipped whole (or a
 * file it couldn't read); a warning only drops one option or reference, and the rest works.
 */
export interface ConfigProblem {
  severity: 'error' | 'warning'
  message: string
}

export interface ConfigFileText {
  name: string
  text: string
  /** Problems found in this file at its last read, then those between files; errors first. */
  problems: ConfigProblem[]
}

export interface ConfigSaveResult {
  saved: boolean
  problems: ConfigProblem[]
}

/**
 * [raw] as a list of problems, errors first: also takes the plain strings an older Cannoli sent,
 * as errors.
 */
export function toProblems(raw: unknown): ConfigProblem[] {
  if (!Array.isArray(raw)) return []
  const out = raw.flatMap((p): ConfigProblem[] => {
    if (typeof p === 'string') return [{ severity: 'error', message: p }]
    if (p && typeof p === 'object' && typeof p.message === 'string') {
      return [{ severity: p.severity === 'warning' ? 'warning' : 'error', message: p.message }]
    }
    return []
  })
  return [...out.filter(p => p.severity === 'error'), ...out.filter(p => p.severity === 'warning')]
}

export async function listConfigFiles(): Promise<string[]> {
  const res = await request('/api/config')
  if (!res.ok) throw await failure(res, 'listConfigFiles')
  return (await res.json()).files
}

export async function getConfigFile(name: string): Promise<ConfigFileText> {
  const res = await request(`/api/config/${encodeURIComponent(name)}`)
  if (!res.ok) throw await failure(res, 'getConfigFile')
  const file = await res.json()
  return { ...file, problems: toProblems(file.problems) }
}

/**
 * Replaces a config file with [text]. Resolves for a saved file (which can still have problems)
 * and for text the server refused (422, `saved: false`); throws for anything else, such as a
 * read-only template (405).
 */
export async function putConfigFile(name: string, text: string): Promise<ConfigSaveResult> {
  const res = await request(`/api/config/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: text,
  })
  if (res.status !== 422 && !res.ok) throw await failure(res, 'putConfigFile')
  const result = await res.json()
  return { saved: result.saved === true, problems: toProblems(result.problems) }
}

/** What the config editor suggests from the device rather than the files (GET /api/config/hints). */
export interface ConfigHints {
  /** Installed apps with a home-screen icon. */
  apps: { package: string; label: string }[]
  /** The libretro cores each emulator package reported having, as systems.json names them. */
  cores: Record<string, string[]>
  /** Folders under Roms/. */
  romFolders: string[]
  /** Game keys as overrides.json uses them. */
  games: string[]
  gamesTruncated?: boolean
}

/** The device's hints for the config editor, or null from a build without them. */
export async function getConfigHints(): Promise<ConfigHints | null> {
  const res = await request('/api/config/hints')
  if (!res.ok) return null
  const body = await res.json()
  return {
    apps: Array.isArray(body.apps) ? body.apps : [],
    cores: body.cores && typeof body.cores === 'object' ? body.cores : {},
    romFolders: Array.isArray(body.romFolders) ? body.romFolders : [],
    games: Array.isArray(body.games) ? body.games : [],
    gamesTruncated: body.gamesTruncated === true,
  }
}
