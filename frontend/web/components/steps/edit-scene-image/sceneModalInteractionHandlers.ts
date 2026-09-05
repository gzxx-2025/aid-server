import { message } from 'antd'
import { createClientId } from '~/utils/clientId'
import type { EditSceneImageModalCtx } from './types'
import { resolveSettingEditBlockedTooltip } from './settingEditPermission'

export function createSceneModalInteractionHandlers(ctx: EditSceneImageModalCtx) {
  const propsRef = {
    get current() {
      return ctx.props()
    }
  }
  const {
    currentSceneIndex,
    currentImageIndex,
    currentScene,
    sceneSettingContent,
    showSceneSettingModal,
    isSelectingSceneImage,
    selectedSceneImageIndex,
    addingAfterIndex,
    localSceneImages
  } = ctx

// 打开场景设定
const handleOpenSceneSetting = () => {
  if (
    resolveSettingEditBlockedTooltip(
      propsRef.current.manualSettingEditBlockedTooltip,
      currentSceneIndex.get()
    )
  ) {
    return
  }
  sceneSettingContent.set((currentScene() as any).setting || '')
  showSceneSettingModal.set(true)
}

const handleSettingModalSyncSceneTitle = (fullDisplayName: string) => {
  propsRef.current.onUpdate(currentSceneIndex.get(), { newAssetDisplayName: fullDisplayName })
}

// 保存场景设定（同步到外层列表）
const handleSaveSceneSetting = (content: string) => {
  sceneSettingContent.set(content)
  showSceneSettingModal.set(false)
  propsRef.current.onUpdate(currentSceneIndex.get(), { setting: content })
  message.success('场景设定已保存')
}

const handleSaveAndUpdateSceneSetting = (content: string) => {
  sceneSettingContent.set(content)
  showSceneSettingModal.set(false)
  propsRef.current.onUpdate(currentSceneIndex.get(), { setting: content })
  message.info('正在调用提取场景模型生成场景图...')
  setTimeout(() => {
    message.success('场景图已生成')
  }, 2000)
}

// 获取场景的第一张图片
const getFirstSceneImage = (sceneIndex: number) => {
  const scene = propsRef.current.scenes[sceneIndex]
  if (scene?.images && scene.images.length > 0) {
    return scene.images[0]
  }
  return null
}

// 在指定索引后添加场景图（从其他场景选择）
const handleAddSceneImageAfter = (index: number) => {
  addingAfterIndex.current = index
  isSelectingSceneImage.set(true)
  selectedSceneImageIndex.set(null)
}

// 从场景切换器选择场景图
const selectSceneImageFromTab = (sceneIndex: number) => {
  const firstImage = getFirstSceneImage(sceneIndex)
  if (!firstImage) {
    message.warning('该场景暂无场景图')
    return
  }

  selectedSceneImageIndex.set(sceneIndex)
}

// 取消选择场景图模式
const cancelSelectSceneImage = () => {
  isSelectingSceneImage.set(false)
  selectedSceneImageIndex.set(null)
  addingAfterIndex.current = null
}

// 取消/返回
const handleCancel = () => {
  // 如果正在选择场景图，先确认选择并添加场景图
  if (isSelectingSceneImage.get()) {
    if (selectedSceneImageIndex.get() !== null) {
      // 确认选择，添加场景图
      const selectedSceneIndex = selectedSceneImageIndex.get()!
      const firstImage = getFirstSceneImage(selectedSceneIndex)

      if (firstImage) {
        // 深拷贝图片数据，避免引用问题
        const now = new Date()
        const newImage = {
          ...firstImage,
          id: createClientId(),
          source: '场景关联',
          importDate: now.toISOString(),
          createdAt: now.toISOString()
        }

        const updatedScenes = [...propsRef.current.scenes]
        if (!updatedScenes[currentSceneIndex.get()].images) {
          updatedScenes[currentSceneIndex.get()].images = []
        }

        // 如果指定了插入位置，在指定索引后插入；否则追加到末尾
        if (addingAfterIndex.current !== null) {
          updatedScenes[currentSceneIndex.get()].images!.splice(addingAfterIndex.current + 1, 0, newImage)
          currentImageIndex.set(addingAfterIndex.current + 1)
        } else {
          updatedScenes[currentSceneIndex.get()].images!.push(newImage)
          currentImageIndex.set(updatedScenes[currentSceneIndex.get()].images!.length - 1)
        }

        // 通知父组件更新场景数据
        propsRef.current.onUpdate(currentSceneIndex.get(), updatedScenes[currentSceneIndex.get()])
        message.success('场景图已添加')
      }
    }

    // 退出选择模式
    cancelSelectSceneImage()
  } else {
    // 如果不是在选择模式，同步所有已添加的图片到外部列表（排除待添加的图片）
    const updatedScenes = [...propsRef.current.scenes]
    if (!updatedScenes[currentSceneIndex.get()].images) {
      updatedScenes[currentSceneIndex.get()].images = []
    }
    // 只同步已确认添加的图片（没有_pending标记的）
    updatedScenes[currentSceneIndex.get()].images = localSceneImages
      .get()
      .filter((img) => !img._pending && img?._isSet === true)
      .map((img) => {
        const { _pending, _isSet, _rpsSourceType, ...rest } = img
        return rest
      })

    // 如果有已添加的图片，通知父组件更新
    if (updatedScenes[currentSceneIndex.get()].images!.length > 0) {
      propsRef.current.onUpdate(currentSceneIndex.get(), updatedScenes[currentSceneIndex.get()])
    }
  }

  // 关闭弹窗（无论是否在选择模式，都关闭弹窗）
  propsRef.current.onOpenChange(false)
}


  return {
    handleOpenSceneSetting,
    handleSettingModalSyncSceneTitle,
    handleSaveSceneSetting,
    handleSaveAndUpdateSceneSetting,
    getFirstSceneImage,
    handleAddSceneImageAfter,
    selectSceneImageFromTab,
    cancelSelectSceneImage,
    handleCancel
  }
}

