export interface ScpCharacterImagesApi {
  handleImportCharacterImage: (index: number) => void
  handleCharacterImageImport: (fileOrAsset: File | string | any) => Promise<void>
  handleEditCharacterImage: (index: number) => void
  handleEditCharacterImageWithIndex: (characterIndex: number, imageIndex: number) => void
  handleCharacterImageUpdate: (characterIndex: number, characterData: any) => Promise<void>
  handleAutoGenerateCharacter: (index: number) => void
  handleImportCharacterFormImage: (characterIndex: number, formIndex: number) => void
  handleCharacterFormImageImport: (fileOrAsset: File | string | any) => Promise<void>
  handleEditCharacterFormImage: (characterIndex: number, formIndex: number) => void
  handleEditCharacterFormImageWithIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleCharacterFormImageUpdate: (formKey: string, formData: any) => void
  handleAutoGenerateCharacterForm: (characterIndex: number, formIndex: number) => Promise<void>
  // 角色图片操作函数
  startEditCharacterImageTitle: (
    characterIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => void
  handleCharacterImageTitleBlur: (characterIndex: number, imageIndex: number) => Promise<void>
  handlePreviewCharacterImageByIndex: (characterIndex: number, imageIndex: number) => void
  handleReplaceCharacterImageByIndex: (characterIndex: number, imageIndex: number) => void
  handleDownloadCharacterImageByIndex: (characterIndex: number, imageIndex: number) => void
  handleDeleteCharacterImageByIndex: (characterIndex: number, imageIndex: number) => Promise<void>
  // 角色形态图片操作函数
  startEditCharacterFormImageTitle: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => void
  handleCharacterFormImageTitleBlur: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => Promise<void>
  handlePreviewCharacterFormImageByIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleReplaceCharacterFormImageByIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleCharacterFormImageMiddleActionByIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleDownloadCharacterFormImageByIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleDeleteCharacterFormImageByIndex: (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => Promise<void>
}
