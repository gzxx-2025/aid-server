import React from 'react';
import { useLocation } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import './AppMain.less';

interface Props {
  children: React.ReactNode;
}

/**
 * 主内容区 - 使用 framer-motion 做温和的过渡：
 *  - 淡入 + 轻微上移（2px）
 *  - 退出时淡出
 *  - 持续 180ms，基本无感但不突兀
 */
export default function AppMain({ children }: Props) {
  const location = useLocation();
  return (
    <div className="app-main">
      <AnimatePresence mode="wait">
        <motion.div
          key={location.pathname}
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -2 }}
          transition={{ duration: 0.22, ease: [0.22, 0.61, 0.36, 1] }}
          className="app-main__inner"
        >
          {children}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
