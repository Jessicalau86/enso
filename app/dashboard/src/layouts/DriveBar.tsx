/** @file Header menubar for the directory listing, containing information about
 * the current directory and some configuration options. */
import * as React from 'react'

import { skipToken, useQuery } from '@tanstack/react-query'

import AddDatalinkIcon from '#/assets/add_datalink.svg'
import AddFolderIcon from '#/assets/add_folder.svg'
import AddKeyIcon from '#/assets/add_key.svg'
import DataDownloadIcon from '#/assets/data_download.svg'
import DataUploadIcon from '#/assets/data_upload.svg'
import Plus2Icon from '#/assets/plus2.svg'
import RightPanelIcon from '#/assets/right_panel.svg'
import { Input as AriaInput } from '#/components/aria'
import { Button, ButtonGroup, DialogTrigger, useVisualTooltip } from '#/components/AriaComponents'
import AssetEventType from '#/events/AssetEventType'
import { useOffline } from '#/hooks/offlineHooks'
import { createGetProjectDetailsQuery } from '#/hooks/projectHooks'
import AssetSearchBar, { type Suggestion } from '#/layouts/AssetSearchBar'
import { useDispatchAssetEvent } from '#/layouts/AssetsTable/EventListProvider'
import {
  isCloudCategory,
  isLocalCategory,
  type Category,
} from '#/layouts/CategorySwitcher/Category'
import StartModal from '#/layouts/StartModal'
import ConfirmDeleteModal from '#/modals/ConfirmDeleteModal'
import UpsertDatalinkModal from '#/modals/UpsertDatalinkModal'
import UpsertSecretModal from '#/modals/UpsertSecretModal'
import { useFullUserSession } from '#/providers/AuthProvider'
import { useCanDownload, useTargetDirectory } from '#/providers/DriveProvider'
import { useInputBindings } from '#/providers/InputBindingsProvider'
import { useSetModal } from '#/providers/ModalProvider'
import { useText } from '#/providers/TextProvider'
import type Backend from '#/services/Backend'
import {
  Plan,
  ProjectState,
  type CreatedProject,
  type Project,
  type ProjectId,
} from '#/services/Backend'
import type AssetQuery from '#/utilities/AssetQuery'
import {
  canPermissionModifyDirectoryContents,
  tryFindSelfPermission,
} from '#/utilities/permissions'
import * as sanitizedEventTargets from '#/utilities/sanitizedEventTargets'

// ================
// === DriveBar ===
// ================

/** Props for a {@link DriveBar}. */
export interface DriveBarProps {
  readonly backend: Backend
  readonly query: AssetQuery
  readonly setQuery: React.Dispatch<React.SetStateAction<AssetQuery>>
  readonly suggestions: readonly Suggestion[]
  readonly category: Category
  readonly isAssetPanelOpen: boolean
  readonly setIsAssetPanelOpen: React.Dispatch<React.SetStateAction<boolean>>
  readonly doEmptyTrash: () => void
  readonly doCreateProject: (
    templateId?: string | null,
    templateName?: string | null,
    onCreated?: (project: CreatedProject) => void,
    onError?: () => void,
  ) => void
  readonly doCreateDirectory: () => void
  readonly doCreateSecret: (name: string, value: string) => void
  readonly doCreateDatalink: (name: string, value: unknown) => void
  readonly doUploadFiles: (files: File[]) => void
}

/** Displays the current directory path and permissions, upload and download buttons,
 * and a column display mode switcher. */
export default function DriveBar(props: DriveBarProps) {
  const { backend, query, setQuery, suggestions, category } = props
  const { doEmptyTrash, doCreateProject, doCreateDirectory } = props
  const { doCreateSecret, doCreateDatalink, doUploadFiles } = props
  const { isAssetPanelOpen, setIsAssetPanelOpen } = props
  const { unsetModal } = useSetModal()
  const { getText } = useText()
  const { user } = useFullUserSession()
  const inputBindings = useInputBindings()
  const dispatchAssetEvent = useDispatchAssetEvent()
  const targetDirectory = useTargetDirectory()
  const createAssetButtonsRef = React.useRef<HTMLDivElement>(null)
  const uploadFilesRef = React.useRef<HTMLInputElement>(null)
  const isCloud = isCloudCategory(category)
  const { isOffline } = useOffline()
  const canDownload = useCanDownload()
  const targetDirectorySelfPermission =
    targetDirectory == null ? null : tryFindSelfPermission(user, targetDirectory.item.permissions)
  const canCreateAssets =
    targetDirectory == null ?
      category.type !== 'cloud' || user.plan == null || user.plan === Plan.solo
    : isLocalCategory(category) ||
      (targetDirectorySelfPermission != null &&
        canPermissionModifyDirectoryContents(targetDirectorySelfPermission.permission))
  const shouldBeDisabled = (isCloud && isOffline) || !canCreateAssets
  const error =
    !shouldBeDisabled ? null
    : isCloud && isOffline ? getText('youAreOffline')
    : getText('cannotCreateAssetsHere')
  const createAssetsVisualTooltip = useVisualTooltip({
    isDisabled: error == null,
    children: error,
    targetRef: createAssetButtonsRef,
    overlayPositionProps: { placement: 'top' },
  })
  const [isCreatingProjectFromTemplate, setIsCreatingProjectFromTemplate] = React.useState(false)
  const [isCreatingProject, setIsCreatingProject] = React.useState(false)
  const [createdProjectId, setCreatedProjectId] = React.useState<ProjectId | null>(null)

  React.useEffect(() => {
    return inputBindings.attach(sanitizedEventTargets.document.body, 'keydown', {
      ...(isCloud ?
        {
          newFolder: () => {
            doCreateDirectory()
          },
        }
      : {}),
      newProject: () => {
        setIsCreatingProject(true)
        doCreateProject(
          null,
          null,
          (project) => {
            setCreatedProjectId(project.projectId)
          },
          () => {
            setIsCreatingProject(false)
          },
        )
      },
      uploadFiles: () => {
        uploadFilesRef.current?.click()
      },
    })
  }, [isCloud, doCreateDirectory, doCreateProject, inputBindings])

  const createdProjectQuery = useQuery<Project | null>(
    createdProjectId ?
      createGetProjectDetailsQuery.createPassiveListener(createdProjectId)
    : { queryKey: ['__IGNORE__'], queryFn: skipToken },
  )

  const isFetching =
    (createdProjectQuery.isLoading ||
      (createdProjectQuery.data &&
        createdProjectQuery.data.state.type !== ProjectState.opened &&
        createdProjectQuery.data.state.type !== ProjectState.closing)) ??
    false

  React.useEffect(() => {
    if (!isFetching) {
      setIsCreatingProject(false)
      setIsCreatingProjectFromTemplate(false)
    }
  }, [isFetching])

  const searchBar = (
    <AssetSearchBar
      backend={backend}
      isCloud={isCloud}
      query={query}
      setQuery={setQuery}
      suggestions={suggestions}
    />
  )

  const assetPanelToggle = (
    <>
      {/* Spacing. */}
      <div className={!isAssetPanelOpen ? 'w-5' : 'hidden'} />
      <div className="absolute right-[15px] top-[27px] z-1">
        <Button
          size="medium"
          variant="custom"
          isActive={isAssetPanelOpen}
          icon={RightPanelIcon}
          aria-label={isAssetPanelOpen ? getText('openAssetPanel') : getText('closeAssetPanel')}
          onPress={() => {
            setIsAssetPanelOpen((isOpen) => !isOpen)
          }}
        />
      </div>
    </>
  )

  switch (category.type) {
    case 'recent': {
      return (
        <ButtonGroup className="my-0.5 grow-0">
          {searchBar}
          {assetPanelToggle}
        </ButtonGroup>
      )
    }
    case 'trash': {
      return (
        <ButtonGroup className="my-0.5 grow-0">
          <DialogTrigger>
            <Button size="medium" variant="outline" isDisabled={shouldBeDisabled}>
              {getText('clearTrash')}
            </Button>
            <ConfirmDeleteModal
              actionText={getText('allTrashedItemsForever')}
              doDelete={doEmptyTrash}
            />
          </DialogTrigger>
          {searchBar}
          {assetPanelToggle}
        </ButtonGroup>
      )
    }
    case 'cloud':
    case 'local':
    case 'user':
    case 'team':
    case 'local-directory': {
      return (
        <ButtonGroup className="my-0.5 grow-0">
          <ButtonGroup
            ref={createAssetButtonsRef}
            className="grow-0"
            {...createAssetsVisualTooltip.targetProps}
          >
            <DialogTrigger>
              <Button
                size="medium"
                variant="accent"
                isDisabled={shouldBeDisabled || isCreatingProject || isCreatingProjectFromTemplate}
                icon={Plus2Icon}
                loading={isCreatingProjectFromTemplate}
                loaderPosition="icon"
              >
                {getText('startWithATemplate')}
              </Button>

              <StartModal
                createProject={(templateId, templateName) => {
                  setIsCreatingProjectFromTemplate(true)
                  doCreateProject(
                    templateId,
                    templateName,
                    (project) => {
                      setCreatedProjectId(project.projectId)
                    },
                    () => {
                      setIsCreatingProjectFromTemplate(false)
                    },
                  )
                }}
              />
            </DialogTrigger>
            <Button
              size="medium"
              variant="outline"
              isDisabled={shouldBeDisabled || isCreatingProject || isCreatingProjectFromTemplate}
              icon={Plus2Icon}
              loading={isCreatingProject}
              loaderPosition="icon"
              onPress={() => {
                setIsCreatingProject(true)
                doCreateProject(
                  null,
                  null,
                  (project) => {
                    setCreatedProjectId(project.projectId)
                    setIsCreatingProject(false)
                  },
                  () => {
                    setIsCreatingProject(false)
                  },
                )
              }}
            >
              {getText('newEmptyProject')}
            </Button>
            <div className="flex h-row items-center gap-4 rounded-full border-0.5 border-primary/20 px-[11px]">
              <Button
                variant="icon"
                size="medium"
                icon={AddFolderIcon}
                isDisabled={shouldBeDisabled}
                aria-label={getText('newFolder')}
                onPress={() => {
                  doCreateDirectory()
                }}
              />
              {isCloud && (
                <DialogTrigger>
                  <Button
                    variant="icon"
                    size="medium"
                    icon={AddKeyIcon}
                    isDisabled={shouldBeDisabled}
                    aria-label={getText('newSecret')}
                  />
                  <UpsertSecretModal id={null} name={null} doCreate={doCreateSecret} />
                </DialogTrigger>
              )}
              {isCloud && (
                <DialogTrigger>
                  <Button
                    variant="icon"
                    size="medium"
                    icon={AddDatalinkIcon}
                    isDisabled={shouldBeDisabled}
                    aria-label={getText('newDatalink')}
                  />
                  <UpsertDatalinkModal doCreate={doCreateDatalink} />
                </DialogTrigger>
              )}
              <AriaInput
                ref={uploadFilesRef}
                type="file"
                multiple
                className="hidden"
                onInput={(event) => {
                  if (event.currentTarget.files != null) {
                    doUploadFiles(Array.from(event.currentTarget.files))
                  }
                  // Clear the list of selected files, otherwise `onInput` will not be
                  // dispatched again if the same file is selected.
                  event.currentTarget.value = ''
                }}
              />
              <Button
                variant="icon"
                size="medium"
                icon={DataUploadIcon}
                isDisabled={shouldBeDisabled}
                aria-label={getText('uploadFiles')}
                onPress={() => {
                  unsetModal()
                  uploadFilesRef.current?.click()
                }}
              />
              <Button
                isDisabled={!canDownload || shouldBeDisabled}
                variant="icon"
                size="medium"
                icon={DataDownloadIcon}
                aria-label={getText('downloadFiles')}
                onPress={() => {
                  unsetModal()
                  dispatchAssetEvent({ type: AssetEventType.downloadSelected })
                }}
              />
            </div>
            {createAssetsVisualTooltip.tooltip}
          </ButtonGroup>
          {searchBar}
          {assetPanelToggle}
        </ButtonGroup>
      )
    }
  }
}
