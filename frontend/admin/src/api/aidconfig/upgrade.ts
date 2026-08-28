import { request } from '@/utils/request';

/** 升级器最近一次任务执行结果 */
export interface UpdaterLastTask {
  taskId?: string;
  /** UPGRADE/UPDATER_UPGRADE/ROLLBACK */
  action?: string;
  /** RUNNING/SUCCESS/FAILED/CANCELLED */
  state?: string;
  message?: string;
  /** 当前进度百分比：0-100 */
  progress?: number;
  /** 当前执行阶段 */
  phase?: string;
  /** 开始时间 */
  startedAt?: string;
  /** 最近进度更新时间 */
  updatedAt?: string;
  finishedAt?: string;
  /** 当前阶段是否允许安全取消 */
  cancellable?: boolean;
  /** 是否已接收取消请求 */
  cancelRequested?: boolean;
  /** 部署配置分项诊断结果 */
  checks?: Record<string, DeploymentCheck>;
}

export interface DeploymentCheck {
  status: 'PASS' | 'FAIL' | 'SKIPPED';
  message: string;
  suggestion?: string;
}

/** 升级器状态 */
export interface UpdaterStatus {
  /** NOT_INSTALLED/STOPPED/AVAILABLE/INCOMPATIBLE/UNKNOWN */
  status: string;
  /** 本地升级器版本 */
  version?: string;
  /** 升级器任务协议版本 */
  protocolVersion?: number;
  /** 发布方最新升级器版本（来自更新清单） */
  latestVersion?: string;
  /** 升级器是否有新版本（支持在线升级） */
  hasUpdate?: boolean;
  /** 部署方式（升级器上报）：systemd=手动部署 / docker=Docker 容器部署 */
  serviceManager?: string;
  message?: string;
  /** 是否可执行一键升级 */
  ready: boolean;
  /** 最近一次任务执行结果 */
  lastTask?: UpdaterLastTask;
  /** 升级器实际加载的脱敏部署配置 */
  deploymentConfig?: DeploymentConfig;
}

/** 在线升级使用的服务器CPU与内存快照 */
export interface UpgradeHostResources {
  /** 操作系统可用逻辑处理器数量 */
  cpuCores?: number;
  /** 操作系统可见总内存，单位字节 */
  totalMemoryBytes?: number;
  /** 触发高风险提醒的CPU核数上限 */
  warningCpuCores?: number;
  /** 触发高风险提醒的内存上限，单位字节 */
  warningMemoryBytes?: number;
  /** 是否完整检测到CPU与内存 */
  detected: boolean;
  /** 是否属于在线升级高风险配置 */
  onlineUpgradeRisk: boolean;
}

/** Docker/systemd 共用的部署运行配置（密钥原文永不回显） */
export interface DeploymentConfig {
  mode: 'docker' | 'systemd';
  configPath: string;
  defaultConfigPath: string;
  allowedConfigRoot: string;
  values: Record<string, string>;
  configuredSecrets: string[];
}

/** 部署配置结构化保存参数；密钥留空表示保持不变 */
export interface DeploymentConfigSaveParams {
  configPath?: string;
  httpPort?: string;
  adminPort?: string;
  backendPort?: string;
  dataRoot?: string;
  mysqlRootPassword?: string;
  mysqlPort?: string;
  dbHost?: string;
  dbPort?: string;
  dbName?: string;
  dbUsername?: string;
  dbPassword?: string;
  redisHost?: string;
  redisPort?: string;
  redisUsername?: string;
  redisPassword?: string;
  clearRedisPassword?: boolean;
  redisDatabase?: string;
  tokenSecret?: string;
  javaOpts?: string;
  dependencyInstallMode?: string;
  dependencyRegion?: string;
  dockerMirrors?: string;
  composeProfiles?: string;
  rocketmqEnabled?: string;
  rocketmqNameserver?: string;
  rocketmqFlushDiskType?: string;
  rocketmqAccessKey?: string;
  rocketmqSecretKey?: string;
  clearRocketmqCredentials?: boolean;
  httpsEnabled?: string;
  httpsPort?: string;
  httpsPublicDomain?: string;
  httpsAdminDomain?: string;
  httpsCertPath?: string;
  httpsKeyPath?: string;
  mysqlBufferPool?: string;
  mysqlMaxConnections?: string;
  redisMaxmemory?: string;
  redisMaxmemoryPolicy?: string;
  webNodeOptions?: string;
  mqNamesrvJavaOpts?: string;
  mqBrokerJavaOpts?: string;
}

export interface DeploymentConfigTestParams extends DeploymentConfigSaveParams {
  targets: Array<'config' | 'dns' | 'certificate' | 'https' | 'mysql' | 'redis' | 'rocketmq'>;
}

/** 官方API地址同步状态 */
export interface OfficialApiStatus {
  remoteBaseUrl?: string;
  localBaseUrl?: string;
  /** 官方API官网地址（注册开通、获取Key入口，来自更新清单） */
  websiteUrl?: string;
  /** 远端地址与本地不一致时为 true */
  changed: boolean;
}

/** 系统版本与升级状态 */
export interface UpgradeStatus {
  currentVersion: string;
  latestVersion?: string;
  /** 最新版本所属渠道：stable=正式版，beta=测试版 */
  latestChannel?: string;
  hasUpdate: boolean;
  /** 允许一键直升的最低版本 */
  minimumVersion?: string;
  /** 当前版本低于最低直升版本时为 true，需先升级中间版本 */
  belowMinimumVersion?: boolean;
  releaseNotes?: string;
  giteeReleaseUrl?: string;
  githubReleaseUrl?: string;
  /** 使用教程/文档地址（发布方随更新清单动态下发） */
  docsUrl?: string;
  /** 提示词开发教程地址（发布方随更新清单动态下发） */
  promptDocsUrl?: string;
  publishedAt?: string;
  checkedAt?: string;
  checkError?: string;
  manifestUrl?: string;
  updaterDownloadUrl?: string;
  updater?: UpdaterStatus;
  /** 在线源码构建使用的服务器资源快照 */
  hostResources?: UpgradeHostResources;
  officialApi?: OfficialApiStatus;
  /** 发布清单允许的近期回退版本 */
  rollbackReleases?: RollbackRelease[];
}

export interface RollbackRelease {
  version: string;
  publishedAt?: string;
  packageUrl?: string;
  sha256?: string;
  databaseRollback?: string;
  databaseCompatible?: boolean;
  notes?: string;
}

/** 升级源配置（地址/路径为自动维护项，保存时不传即不修改） */
export interface UpgradeSourceSetting {
  /** 版本更新清单地址（自动维护，页面只读展示） */
  manifestUrl?: string;
  /** 升级器下载地址（自动维护） */
  updaterDownloadUrl?: string;
  /** 升级器健康文件路径（自动维护） */
  updaterHealthFile?: string;
  /** 升级器任务文件路径（自动维护） */
  updaterTaskFile?: string;
  /** 接收版本渠道：stable=仅正式版，all=正式版+测试版 */
  releaseChannel?: string;
  /** 升级前自动备份的保留份数（1-50，默认 3） */
  keepBackups?: number;
}

/** 官方统一网关设置 */
export interface OfficialGatewaySetting {
  enabled: boolean;
  baseUrl?: string;
  apiKeyMasked?: string;
  hasApiKey: boolean;
  /** 例外模型ID列表（例外模型仍走自有厂商网关） */
  excludedModelIds?: number[];
  /** 例外厂商ID列表（例外厂商下全部模型仍走自有厂商网关） */
  excludedProviderIds?: number[];
}

/** 官方统一网关保存参数 */
export interface OfficialGatewaySaveParams {
  enabled: boolean;
  baseUrl?: string;
  /** 留空表示不修改密钥 */
  apiKey?: string;
  /** 例外模型ID列表（不传表示不修改，空数组表示清空） */
  excludedModelIds?: number[];
  /** 例外厂商ID列表（不传表示不修改，空数组表示清空） */
  excludedProviderIds?: number[];
}

/** 官方教程文档地址集合（随更新清单静默刷新，后端从缓存返回） */
export interface DocLinks {
  /** 使用教程/文档地址 */
  docsUrl?: string;
  /** 提示词开发教程地址 */
  promptDocsUrl?: string;
  /** 地址最近一次刷新时间 */
  refreshedAt?: string;
}

/** 查询官方教程文档地址（后端读缓存，不回源更新清单） */
export function getDocLinks() {
  return request<DocLinks>({
    url: '/aidconfig/upgrade/doc-links',
    method: 'get'
  });
}

/** 升级器运行日志（安装引导弹窗与故障排查展示） */
export interface UpdaterLog {
  /** 日志文件路径 */
  logFile?: string;
  /** 日志尾部内容（按行，最多最近200行） */
  lines?: string[];
  /** 日志不可读时的原因说明 */
  message?: string;
}

/** 官方资源包初始化状态 */
export interface OfficialAssetsStatus {
  initialized: boolean;
  fileCount: number;
  totalBytes: number;
  targetDirectory: string;
  recommendedArchiveName: string;
  maxUploadBytes: number;
  manualCommand: string;
  message?: string;
}

/** 查询官方资源包初始化状态 */
export function getOfficialAssetsStatus() {
  return request<OfficialAssetsStatus>({
    url: '/aidconfig/upgrade/official-assets/status',
    method: 'get'
  });
}

/** 上传并初始化官方资源包 */
export function installOfficialAssets(file: File, onProgress?: (percent: number) => void) {
  const formData = new FormData();
  formData.append('file', file, file.name);
  return request<OfficialAssetsStatus>({
    url: '/aidconfig/upgrade/official-assets/install',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data',
      repeatSubmit: false
    },
    timeout: 30 * 60 * 1000,
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.min(99, Math.round((event.loaded * 100) / event.total)));
      }
    }
  });
}

/** 查询升级器最近运行日志 */
export function getUpdaterLogs() {
  return request<UpdaterLog>({
    url: '/aidconfig/upgrade/updater/logs',
    method: 'get'
  });
}

/** 查询升级器当前实际加载的部署运行配置 */
export function getDeploymentConfig() {
  return request<DeploymentConfig>({
    url: '/aidconfig/upgrade/deployment-config',
    method: 'get'
  });
}

/** 只校验部署配置，不写入、不重启 */
export function validateDeploymentConfig(data: DeploymentConfigSaveParams) {
  return request<void>({
    url: '/aidconfig/upgrade/deployment-config/validate',
    method: 'post',
    data
  });
}

/** 备份、应用部署配置并由升级器重启服务 */
export function applyDeploymentConfig(data: DeploymentConfigSaveParams) {
  return request<void>({
    url: '/aidconfig/upgrade/deployment-config/apply',
    method: 'post',
    data
  });
}

/** 恢复上一份部署配置并重启服务 */
export function rollbackDeploymentConfig() {
  return request<void>({
    url: '/aidconfig/upgrade/deployment-config/rollback',
    method: 'post'
  });
}

/** 由升级器在服务器侧执行部署配置分项诊断；不会写配置或重启。 */
export function testDeploymentConfig(data: DeploymentConfigTestParams) {
  return request<void>({
    url: '/aidconfig/upgrade/deployment-config/test',
    method: 'post',
    data
  });
}

/** 安全上传完整证书链与私钥；私钥仅进入升级器受控暂存目录。 */
export function installHttpsCertificate(
  certificate: File,
  privateKey: File,
  options: Pick<DeploymentConfigSaveParams, 'configPath' | 'httpsPublicDomain' | 'httpsAdminDomain'>,
  onProgress?: (percent: number) => void
) {
  const formData = new FormData();
  formData.append('certificate', certificate, certificate.name);
  formData.append('privateKey', privateKey, privateKey.name);
  if (options.configPath) formData.append('configPath', options.configPath);
  if (options.httpsPublicDomain) formData.append('httpsPublicDomain', options.httpsPublicDomain);
  if (options.httpsAdminDomain) formData.append('httpsAdminDomain', options.httpsAdminDomain);
  return request<void>({
    url: '/aidconfig/upgrade/deployment-config/certificate',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false },
    timeout: 2 * 60 * 1000,
    onUploadProgress: (event) => {
      if (event.total && onProgress) onProgress(Math.min(99, Math.round((event.loaded * 100) / event.total)));
    }
  });
}

/** 查询系统版本与升级状态（走后端缓存） */
export function getUpgradeStatus() {
  return request<UpgradeStatus>({
    url: '/aidconfig/upgrade/status',
    method: 'get'
  });
}

/** 手动检查更新（强制回源更新清单） */
export function checkUpgrade() {
  return request<UpgradeStatus>({
    url: '/aidconfig/upgrade/check',
    method: 'post'
  });
}

/** 提交一键升级任务 */
export function startUpgrade() {
  return request<void>({
    url: '/aidconfig/upgrade/start',
    method: 'post'
  });
}

/** 安全取消当前系统升级或版本回退任务 */
export function cancelUpgrade() {
  return request<void>({
    url: '/aidconfig/upgrade/cancel',
    method: 'post'
  });
}

/** 提交升级器在线升级任务（升级器下载新版并自替换重启） */
export function startUpdaterUpgrade() {
  return request<void>({
    url: '/aidconfig/upgrade/updater/upgrade',
    method: 'post'
  });
}

/** 提交版本回退任务 */
export function rollbackSystem(targetVersion: string) {
  return request<void>({
    url: '/aidconfig/upgrade/rollback',
    method: 'post',
    data: { targetVersion }
  });
}

/** 查询升级源配置 */
export function getUpgradeSource() {
  return request<UpgradeSourceSetting>({
    url: '/aidconfig/upgrade/source',
    method: 'get'
  });
}

/** 保存升级源配置（修改后立即生效） */
export function saveUpgradeSource(data: UpgradeSourceSetting) {
  return request<void>({
    url: '/aidconfig/upgrade/source',
    method: 'post',
    data
  });
}

/** 查询官方统一网关设置 */
export function getOfficialGateway() {
  return request<OfficialGatewaySetting>({
    url: '/aidconfig/upgrade/official-gateway',
    method: 'get'
  });
}

/** 保存官方统一网关设置 */
export function saveOfficialGateway(data: OfficialGatewaySaveParams) {
  return request<void>({
    url: '/aidconfig/upgrade/official-gateway',
    method: 'post',
    data
  });
}

/** 手动获取更新清单中的官方API地址（只比对，不写入） */
export function fetchOfficialApi() {
  return request<OfficialApiStatus>({
    url: '/aidconfig/upgrade/official-api/fetch',
    method: 'post'
  });
}

/** 将更新清单中的官方API地址应用到本地配置 */
export function applyOfficialApi() {
  return request<OfficialApiStatus>({
    url: '/aidconfig/upgrade/official-api/apply',
    method: 'post'
  });
}
