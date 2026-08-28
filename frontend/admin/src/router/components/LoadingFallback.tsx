import React from 'react';
import { Spin } from 'antd';

export default function LoadingFallback() {
  return (
    <div
      style={{
        height: '60vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }}
    >
      <Spin size="large" />
    </div>
  );
}
