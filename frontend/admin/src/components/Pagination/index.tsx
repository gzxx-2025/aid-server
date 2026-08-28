import React from 'react';
import { Pagination as AntPagination } from 'antd';
import './style.less';

interface Props {
  total: number;
  page: number;
  limit: number;
  pageSizes?: number[];
  onChange: (page: number, limit: number) => void;
  hidden?: boolean;
}

export default function Pagination({
  total,
  page,
  limit,
  pageSizes = [10, 20, 30, 50],
  onChange,
  hidden
}: Props) {
  if (hidden || total <= 0) return null;
  return (
    <div className="page-pagination">
      <AntPagination
        current={page}
        pageSize={limit}
        total={total}
        showSizeChanger
        showQuickJumper
        showTotal={(t) => `共 ${t} 条`}
        pageSizeOptions={pageSizes.map(String)}
        onChange={(p, s) => onChange(p, s)}
      />
    </div>
  );
}
