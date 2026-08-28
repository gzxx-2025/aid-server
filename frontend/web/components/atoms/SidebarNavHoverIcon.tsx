'use client'

import './SidebarNavHoverIcon.css'

const GALLERY_PATH =
  'M12.125 3.00391C12.4301 3.02201 12.7274 3.10297 12.9932 3.24121L19.9883 6.8418C20.2916 6.9996 20.5434 7.22681 20.7188 7.5C20.8941 7.77334 20.9869 8.08369 20.9873 8.39941V11.6953L21.001 11.7207L20.9873 11.7275V15.6006C20.9869 15.9163 20.8941 16.2267 20.7188 16.5C20.5434 16.7732 20.2917 17.0004 19.9883 17.1582L12.9932 20.7588C12.6895 20.9167 12.3448 20.9999 11.9941 21L11.8623 20.9961C11.5572 20.978 11.2599 20.897 10.9941 20.7588L3.99902 17.1582C3.73366 17.0201 3.50772 16.8291 3.33789 16.6006L3.26855 16.5C3.09316 16.2267 3.00036 15.9163 3 15.6006V8.39941C3.00031 8.12325 3.07093 7.85121 3.20605 7.60449L3.26855 7.5C3.44392 7.22681 3.69566 6.9996 3.99902 6.8418L10.9941 3.24121C11.298 3.08319 11.6433 3 11.9941 3L12.125 3.00391ZM4.21387 7.91113C4.17379 7.95249 4.13894 7.99554 4.11035 8.04004C4.03504 8.1574 4.00015 8.28148 4 8.40039V10.9297L4.22754 11.0479L8.29688 13.1416C8.46322 13.2273 8.56829 13.3988 8.56836 13.5859V18.3848L11.4521 19.8701L11.4561 19.8721C11.537 19.9141 11.6256 19.946 11.7188 19.9678V11.8301L4.26758 7.93945L4.21387 7.91113ZM19.707 8.44531L12.7188 11.8398V19.7754L15.8682 18.1533V13.6807C15.8682 13.4981 15.9688 13.3299 16.1289 13.2422L19.9873 11.1338V8.40039C19.9873 8.37101 19.9848 8.34135 19.9805 8.31152L19.707 8.44531ZM4 15.5996L4.00684 15.6895C4.02009 15.7801 4.0538 15.8718 4.11035 15.96C4.18639 16.0784 4.30375 16.1897 4.46094 16.2715L7.56836 17.8701V13.8896L4 12.0537V15.5996ZM16.8682 13.9775V17.6387L19.5254 16.2715C19.6826 16.1897 19.8009 16.0784 19.877 15.96C19.9521 15.8428 19.987 15.7193 19.9873 15.6006V12.2734L16.8682 13.9775ZM5.24707 7.32227L12.2256 10.9668L19.2188 7.57031L16.1338 5.98145L16.1553 6.02832L15.7012 6.23828L12.6328 7.6582C12.4953 7.72179 12.3359 7.71867 12.2002 7.65137L8.77344 5.94531L8.34082 5.73047L5.24707 7.32227ZM11.9941 4C11.8 4 11.6137 4.04602 11.4561 4.12793L11.4521 4.12988L9.44434 5.16309L12.4307 6.64844L15.0625 5.43066L12.5352 4.12988L12.5312 4.12793C12.3737 4.04609 12.1881 4.00008 11.9941 4Z'

const WORKS_PATH =
  'M18.6494 2.99902C19.9489 2.99926 21.0029 4.05299 21.0029 5.35254V18.6484C21.0027 19.9478 19.9488 21.0017 18.6494 21.002H5.35352C4.05396 21.002 3.00023 19.9479 3 18.6484V5.35254C3 4.05284 4.05382 2.99902 5.35352 2.99902H18.6494ZM4 18.6484C4.00023 19.3957 4.60625 20.002 5.35352 20.002H7.25V16.751H4V18.6484ZM8.25 16.2412C8.25006 16.2444 8.25098 16.2478 8.25098 16.251C8.25098 16.2539 8.25005 16.2568 8.25 16.2598V20.002H15.752V12.5H8.25V16.2412ZM16.752 20.002H18.6494C19.3965 20.0017 20.0027 19.3955 20.0029 18.6484V16.751H16.752V20.002ZM4 15.751H7.25V12.5H4V15.751ZM16.752 15.751H20.0029V12.5H16.752V15.751ZM4 11.5H7.25V8.24902H4V11.5ZM8.25 7.73926C8.25006 7.74251 8.25097 7.74576 8.25098 7.74902C8.25098 7.75186 8.25005 7.75499 8.25 7.75781V11.5H15.752V3.99902H8.25V7.73926ZM16.752 8.24902V11.5H20.0029V8.24902H16.752ZM16.752 7.24902H20.0029V5.35254C20.0029 4.60528 19.3966 3.99926 18.6494 3.99902H16.752V7.24902ZM5.35352 3.99902C4.6061 3.99902 4 4.60513 4 5.35254V7.24902H7.25V3.99902H5.35352Z'

interface SidebarNavHoverIconProps {
  type: 'gallery' | 'works' | 'assets'
  className?: string
}

export default function SidebarNavHoverIcon({ type, className }: SidebarNavHoverIconProps) {
  const rootClass = [`sidebar-nav-hover-icon`, `sidebar-nav-hover-icon--${type}`, className]
    .filter(Boolean)
    .join(' ')

  return (
    <span className={rootClass} aria-hidden="true">
      {type === 'gallery' ? (
        /* 案例广场：原图 fill 静态层 + hover 描摹与翻起 */
        <svg
          className="sidebar-nav-hover-icon__svg"
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <g className="sidebar-nav-hover-icon__draw-flap">
            <path className="sidebar-nav-hover-icon__fill" d={GALLERY_PATH} fill="currentColor" />
            <path
              className="sidebar-nav-hover-icon__stroke-trace"
              pathLength={1}
              d={GALLERY_PATH}
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </g>
        </svg>
      ) : type === 'works' ? (
        /* 我的作品：原图 fill 静态层 + hover 描摹与翻起 */
        <svg
          className="sidebar-nav-hover-icon__svg"
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <g className="sidebar-nav-hover-icon__draw-flap">
            <path className="sidebar-nav-hover-icon__fill" d={WORKS_PATH} fill="currentColor" />
            <path
              className="sidebar-nav-hover-icon__stroke-trace"
              pathLength={1}
              d={WORKS_PATH}
              fill="none"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </g>
        </svg>
      ) : (
        /* 资产库：原图 stroke 静态层 + hover 描边重绘与翻盖 */
        <svg
          className="sidebar-nav-hover-icon__svg"
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <g className="sidebar-nav-hover-icon__draw-flap">
            <path
              className="sidebar-nav-hover-icon__stroke"
              pathLength={1}
              d="M19.3116 11V8.44444C19.3116 7.97295 19.1094 7.52076 18.7495 7.18737C18.3897 6.85397 17.9015 6.66667 17.3926 6.66667H11.6356L9.71654 4H4.91901C4.41006 4 3.92195 4.1873 3.56207 4.5207C3.20218 4.8541 3 5.30628 3 5.77778V18.2222C3 18.6937 3.20218 19.1459 3.56207 19.4793C3.92195 19.8127 4.41006 20 4.91901 20H17.3926C17.9015 20 18.3056 20 18.7495 19.4793C19.1935 18.9586 19.1223 18.8824 19.3611 18.5"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </g>
          <path
            className="sidebar-nav-hover-icon__stroke sidebar-nav-hover-icon__stroke--body"
            pathLength={1}
            d="M19.3613 18.5L21.5316 12.3319C21.7604 11.6814 21.2778 11 20.5883 11H7.28371C6.8689 11 6.49716 11.2561 6.34935 11.6437L3.54395 19"
            stroke="currentColor"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </span>
  )
}
