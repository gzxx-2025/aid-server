/** 后端通用响应体 */
export interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
  total?: number;
  rows?: T[];
  [key: string]: any;
}

/** 分页参数 */
export interface PageQuery {
  pageNum?: number;
  pageSize?: number;
  orderByColumn?: string;
  isAsc?: 'asc' | 'desc';
  [key: string]: any;
}

/** 分页结果 */
export interface PageResult<T> {
  rows: T[];
  total: number;
}

/** 后端返回的原始路由节点 */
export interface BackendRoute {
  name?: string;
  path: string;
  hidden?: boolean;
  redirect?: string;
  component?: string;
  alwaysShow?: boolean;
  query?: string;
  meta?: {
    title?: string;
    icon?: string;
    noCache?: boolean;
    link?: string | null;
    affix?: boolean;
    activeMenu?: string;
    breadcrumb?: boolean;
  };
  children?: BackendRoute[];
  permissions?: string[];
  roles?: string[];
}

/** 用户信息 */
export interface UserInfo {
  userId: number | string;
  userName: string;
  nickName: string;
  avatar?: string;
  email?: string;
  phonenumber?: string;
  sex?: string;
  [key: string]: any;
}
