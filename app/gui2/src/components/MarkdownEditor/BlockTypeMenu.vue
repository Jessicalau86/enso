<script setup lang="ts">
import { blockTypeToBlockName, type BlockType } from '@/components/MarkdownEditor/formatting'
import SelectionDropdown from '@/components/SelectionDropdown.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { Icon } from '@/util/iconName'

const blockType = defineModel<BlockType>({ required: true })

const blockTypeIcon: Record<keyof typeof blockTypeToBlockName, Icon> = {
  paragraph: 'text',
  bullet: 'bullet-list',
  code: 'code',
  h1: 'header1',
  h2: 'header2',
  h3: 'header3',
  number: 'numbered-list',
  quote: 'quote',
}
const blockTypesOrdered: BlockType[] = [
  'paragraph',
  'h1',
  'h2',
  'h3',
  'code',
  'bullet',
  'number',
  'quote',
]
</script>

<template>
  <SelectionDropdown v-model="blockType" :values="blockTypesOrdered">
    <template #default="{ value }">
      <SvgIcon :name="blockTypeIcon[value]" />
      <div class="iconLabel" v-text="blockTypeToBlockName[value]" />
    </template>
  </SelectionDropdown>
</template>
