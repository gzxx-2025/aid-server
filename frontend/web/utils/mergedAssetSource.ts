/** 合并资产分页 sourceFlag：custom=个人 / official=官方（接口文档）；兼容历史 USER/OFFICIAL */
export function isMergedAssetUserOwned(sourceFlag: string | null | undefined): boolean {
  const flag = String(sourceFlag || '').trim().toLowerCase()
  return flag === 'custom' || flag === 'user'
}

export function isMergedAssetOfficial(sourceFlag: string | null | undefined): boolean {
  const flag = String(sourceFlag || '').trim().toLowerCase()
  return flag === 'official' || flag === 'offical'
}
