<script setup lang="ts">
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import SvgButton from '@/components/SvgButton.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import { useBackendQuery, useBackendQueryPrefetching } from '@/composables/backend'
import type { ToValue } from '@/util/reactivity'
import type {
  DirectoryAsset,
  DirectoryId,
  FileAsset,
  FileId,
} from 'enso-common/src/services/Backend'
import Backend, { assetIsDirectory, assetIsFile } from 'enso-common/src/services/Backend'
import { computed, ref, toValue, watch } from 'vue'

const emit = defineEmits<{
  pathSelected: [path: string]
}>()

const { prefetch, ensureQueryData } = useBackendQueryPrefetching()

// === Current Directory ===

interface Directory {
  id: DirectoryId | null
  title: string
}

const directoryStack = ref<Directory[]>([
  {
    id: null,
    title: 'Cloud',
  },
])
const currentDirectory = computed(() => directoryStack.value[directoryStack.value.length - 1]!)

// === Directory Contents ===

function listDirectoryArgs(params: ToValue<Directory | undefined>) {
  return computed<Parameters<Backend['listDirectory']> | undefined>(() => {
    const paramsValue = toValue(params)
    return paramsValue ?
        [
          {
            parentId: paramsValue.id,
            filterBy: null,
            labels: null,
            recentProjects: false,
          },
          paramsValue.title,
        ]
      : undefined
  })
}

const { isPending, isError, data, error } = useBackendQuery(
  'listDirectory',
  listDirectoryArgs(currentDirectory),
)
const compareTitle = (a: { title: string }, b: { title: string }) => a.title.localeCompare(b.title)
const directories = computed(
  () => data.value && data.value.filter<DirectoryAsset>(assetIsDirectory).sort(compareTitle),
)
const files = computed(
  () => data.value && data.value.filter<FileAsset>(assetIsFile).sort(compareTitle),
)
const isEmpty = computed(() => directories.value?.length === 0 && files.value?.length === 0)

// === Selected File ===

interface File {
  id: FileId
  title: string
}

const selectedFile = ref<File>()

function getFileDetailsArgs(parameters: ToValue<File | undefined>) {
  return computed<Parameters<Backend['getFileDetails']> | undefined>(() => {
    const paramsValue = toValue(parameters)
    return paramsValue ? [paramsValue.id, paramsValue.title] : undefined
  })
}

const selectedFileDetails = useBackendQuery('getFileDetails', getFileDetailsArgs(selectedFile))

// === Prefetching ===

watch(directories, (directories) => {
  // Prefetch directories to avoid lag when the user navigates, but only if we don't already have stale data.
  // When the user opens a directory with stale data, it will refresh and the animation will show what files have
  // changed since they last viewed.
  for (const directory of directories ?? [])
    ensureQueryData('listDirectory', listDirectoryArgs(directory))
})

watch(files, (files) => {
  // Prefetch file info to avoid lag when the user makes a selection.
  for (const file of files ?? []) prefetch('getFileDetails', getFileDetailsArgs(file))
})

// === Interactivity ===

function enterDir(dir: DirectoryAsset) {
  directoryStack.value.push(dir)
}

function popTo(index: number) {
  directoryStack.value.splice(index + 1)
}

function chooseFile(file: FileAsset) {
  selectedFile.value = file
}

const isBusy = computed(
  () => isPending.value || (selectedFile.value && selectedFileDetails.isPending.value),
)

const anyError = computed(() =>
  isError.value ? error
  : selectedFileDetails.isError.value ? selectedFileDetails.error
  : undefined,
)

watch(selectedFileDetails.data, (details) => {
  if (details) emit('pathSelected', details.file.path)
})
</script>

<template>
  <div class="FileBrowserWidget">
    <div class="directoryStack">
      <TransitionGroup>
        <template v-for="(directory, index) in directoryStack" :key="directory.id ?? 'root'">
          <SvgIcon v-if="index > 0" name="arrow_right_head_only" />
          <div
            class="clickable"
            :class="{ nonInteractive: index === directoryStack.length - 1 }"
            @click.stop="popTo(index)"
            v-text="directory.title"
          ></div>
        </template>
      </TransitionGroup>
    </div>
    <div v-if="isBusy" class="contents centerContent"><LoadingSpinner /></div>
    <div v-else-if="anyError" class="contents centerContent">Error: {{ anyError }}</div>
    <div v-else-if="isEmpty" class="contents centerContent">Directory is empty</div>
    <div v-else :key="currentDirectory.id ?? 'root'" class="contents listing">
      <TransitionGroup>
        <div v-for="entry in directories" :key="entry.id">
          <SvgButton :label="entry.title" name="folder" class="entry" @click="enterDir(entry)" />
        </div>
        <div v-for="entry in files" :key="entry.id">
          <SvgButton :label="entry.title" name="text2" class="entry" @click="chooseFile(entry)" />
        </div>
      </TransitionGroup>
    </div>
  </div>
</template>

<style scoped>
.FileBrowserWidget {
  --border-width: 2px;
  --border-radius-inner: calc(var(--radius-default) - var(--border-width));
  background-color: var(--background-color);
  padding: var(--border-width);
  border-radius: 0 0 var(--radius-default) var(--radius-default);
  min-width: 400px;
  min-height: 200px;
  max-height: 600px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.directoryStack {
  --transition-duration: 0.1s;
  color: white;
  padding: 2px;
  gap: 2px;
  background-color: var(--background-color);
  display: flex;
  align-items: center;
}

.contents {
  flex: 1;
  width: 100%;
  background-color: var(--color-frame-selected-bg);
  border-radius: 0 0 var(--border-radius-inner) var(--border-radius-inner);
}

.listing {
  --transition-duration: 0.5s;
  padding: 8px;
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: start;
  justify-content: start;
  gap: 8px;
}

.centerContent {
  display: flex;
  align-items: center;
  justify-content: center;
}

.entry {
  width: 100%;
  justify-content: start;
}

.nonInteractive {
  pointer-events: none;
}

.v-move,
.v-enter-active,
.v-leave-active {
  transition: all var(--transition-duration) ease;
}
.v-enter-from,
.v-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
.list-leave-active {
  position: absolute;
}
</style>
