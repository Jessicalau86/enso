/** @file A context menu for an `AssetsTable`, when no row is selected, or multiple rows
 * are selected. */
import * as React from 'react'

import * as authProvider from '#/providers/AuthProvider'
import { useSelectedKeys, useSetSelectedKeys } from '#/providers/DriveProvider'
import * as modalProvider from '#/providers/ModalProvider'
import * as textProvider from '#/providers/TextProvider'

import AssetEventType from '#/events/AssetEventType'

import * as eventListProvider from '#/layouts/AssetsTable/EventListProvider'
import { type Category, isCloudCategory } from '#/layouts/CategorySwitcher/Category'
import GlobalContextMenu from '#/layouts/GlobalContextMenu'

import ContextMenu from '#/components/ContextMenu'
import ContextMenuEntry from '#/components/ContextMenuEntry'
import ContextMenus from '#/components/ContextMenus'

import ConfirmDeleteModal from '#/modals/ConfirmDeleteModal'

import type Backend from '#/services/Backend'
import * as backendModule from '#/services/Backend'

import type * as assetTreeNode from '#/utilities/AssetTreeNode'
import type * as pasteDataModule from '#/utilities/pasteData'
import * as permissions from '#/utilities/permissions'
import { EMPTY_SET } from '#/utilities/set'
import * as uniqueString from '#/utilities/uniqueString'

// =================
// === Constants ===
// =================

/** Props for an {@link AssetsTableContextMenu}. */
export interface AssetsTableContextMenuProps {
  readonly hidden?: boolean
  readonly backend: Backend
  readonly category: Category
  readonly rootDirectoryId: backendModule.DirectoryId
  readonly pasteData: pasteDataModule.PasteData<ReadonlySet<backendModule.AssetId>> | null
  readonly nodeMapRef: React.MutableRefObject<
    ReadonlyMap<backendModule.AssetId, assetTreeNode.AnyAssetTreeNode>
  >
  readonly event: Pick<React.MouseEvent<Element, MouseEvent>, 'pageX' | 'pageY'>
  readonly doCopy: () => void
  readonly doCut: () => void
  readonly doPaste: (
    newParentKey: backendModule.DirectoryId,
    newParentId: backendModule.DirectoryId,
  ) => void
  readonly doDelete: (assetId: backendModule.AssetId, forever?: boolean) => Promise<void>
}

/** A context menu for an `AssetsTable`, when no row is selected, or multiple rows
 * are selected. */
export default function AssetsTableContextMenu(props: AssetsTableContextMenuProps) {
  const { hidden = false, backend, category, pasteData } = props
  const { nodeMapRef, event, rootDirectoryId } = props
  const { doCopy, doCut, doPaste, doDelete } = props
  const { user } = authProvider.useFullUserSession()
  const { setModal, unsetModal } = modalProvider.useSetModal()
  const { getText } = textProvider.useText()
  const isCloud = isCloudCategory(category)
  const dispatchAssetEvent = eventListProvider.useDispatchAssetEvent()
  const selectedKeys = useSelectedKeys()
  const setSelectedKeys = useSetSelectedKeys()

  // This works because all items are mutated, ensuring their value stays
  // up to date.
  const ownsAllSelectedAssets =
    !isCloud ||
    Array.from(selectedKeys, (key) => {
      const userPermissions = nodeMapRef.current.get(key)?.item.permissions
      const selfPermission = userPermissions?.find(
        backendModule.isUserPermissionAnd((permission) => permission.user.userId === user.userId),
      )
      return selfPermission?.permission === permissions.PermissionAction.own
    }).every((isOwner) => isOwner)

  // This is not a React component even though it contains JSX.
  // eslint-disable-next-line no-restricted-syntax
  const doDeleteAll = () => {
    if (isCloud) {
      unsetModal()

      for (const key of selectedKeys) {
        void doDelete(key, false)
      }
    } else {
      const [firstKey] = selectedKeys
      const soleAssetName =
        firstKey != null ? nodeMapRef.current.get(firstKey)?.item.title ?? '(unknown)' : '(unknown)'
      setModal(
        <ConfirmDeleteModal
          defaultOpen
          actionText={
            selectedKeys.size === 1 ?
              getText('deleteSelectedAssetActionText', soleAssetName)
            : getText('deleteSelectedAssetsActionText', selectedKeys.size)
          }
          doDelete={() => {
            setSelectedKeys(EMPTY_SET)

            for (const key of selectedKeys) {
              void doDelete(key, false)
            }
          }}
        />,
      )
    }
  }

  if (category.type === 'trash') {
    return selectedKeys.size === 0 ?
        null
      : <ContextMenus key={uniqueString.uniqueString()} hidden={hidden} event={event}>
          <ContextMenu aria-label={getText('assetsTableContextMenuLabel')} hidden={hidden}>
            <ContextMenuEntry
              hidden={hidden}
              action="undelete"
              label={getText('restoreAllFromTrashShortcut')}
              doAction={() => {
                unsetModal()
                dispatchAssetEvent({ type: AssetEventType.restore, ids: selectedKeys })
              }}
            />
            {isCloud && (
              <ContextMenuEntry
                hidden={hidden}
                action="delete"
                label={getText('deleteAllForeverShortcut')}
                doAction={() => {
                  const [firstKey] = selectedKeys
                  const soleAssetName =
                    firstKey != null ?
                      nodeMapRef.current.get(firstKey)?.item.title ?? '(unknown)'
                    : '(unknown)'
                  setModal(
                    <ConfirmDeleteModal
                      defaultOpen
                      actionText={
                        selectedKeys.size === 1 ?
                          getText('deleteSelectedAssetForeverActionText', soleAssetName)
                        : getText('deleteSelectedAssetsForeverActionText', selectedKeys.size)
                      }
                      doDelete={() => {
                        setSelectedKeys(EMPTY_SET)
                        dispatchAssetEvent({
                          type: AssetEventType.deleteForever,
                          ids: selectedKeys,
                        })
                      }}
                    />,
                  )
                }}
              />
            )}
          </ContextMenu>
        </ContextMenus>
  } else if (category.type === 'recent') {
    return null
  } else {
    return (
      <ContextMenus key={uniqueString.uniqueString()} hidden={hidden} event={event}>
        {selectedKeys.size !== 0 && (
          <ContextMenu aria-label={getText('assetsTableContextMenuLabel')} hidden={hidden}>
            {ownsAllSelectedAssets && (
              <ContextMenuEntry
                hidden={hidden}
                action="delete"
                label={isCloud ? getText('moveAllToTrashShortcut') : getText('deleteAllShortcut')}
                doAction={doDeleteAll}
              />
            )}
            {isCloud && (
              <ContextMenuEntry
                hidden={hidden}
                action="copy"
                label={getText('copyAllShortcut')}
                doAction={doCopy}
              />
            )}
            {ownsAllSelectedAssets && (
              <ContextMenuEntry
                hidden={hidden}
                action="cut"
                label={getText('cutAllShortcut')}
                doAction={doCut}
              />
            )}
            {pasteData != null && pasteData.data.size > 0 && (
              <ContextMenuEntry
                hidden={hidden}
                action="paste"
                label={getText('pasteAllShortcut')}
                doAction={() => {
                  const [firstKey] = selectedKeys
                  const selectedNode =
                    selectedKeys.size === 1 && firstKey != null ?
                      nodeMapRef.current.get(firstKey)
                    : null
                  if (selectedNode?.type === backendModule.AssetType.directory) {
                    doPaste(selectedNode.key, selectedNode.item.id)
                  } else {
                    doPaste(rootDirectoryId, rootDirectoryId)
                  }
                }}
              />
            )}
          </ContextMenu>
        )}
        {(category.type !== 'cloud' ||
          user.plan == null ||
          user.plan === backendModule.Plan.solo) && (
          <GlobalContextMenu
            hidden={hidden}
            backend={backend}
            hasPasteData={pasteData != null}
            rootDirectoryId={rootDirectoryId}
            directoryKey={null}
            directoryId={null}
            doPaste={doPaste}
          />
        )}
      </ContextMenus>
    )
  }
}
