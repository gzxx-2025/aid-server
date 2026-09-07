'use client'

/* eslint-disable @next/next/no-img-element -- 只读展示服务端素材 URL。 */
import type { StudioFlowAssetFormPreview } from '@/types/studio'
import { openImagePreviewModal } from '@/utils/openImagePreviewModal'

export function StudioFlowAssetGallery({
  assetTitle,
  forms
}: {
  assetTitle: string
  forms: StudioFlowAssetFormPreview[]
}) {
  return (
    <section className="studio-flow-asset-gallery nodrag nowheel" aria-label={`${assetTitle}形态列表`}>
      <div className="studio-flow-asset-gallery__track">
        {forms.map((form) => (
          <article key={form.formId} className="studio-flow-asset-gallery__form">
            <header>
              <strong>{form.name}</strong>
              <span>{form.images.length} 张图</span>
            </header>
            {form.prompt ? <p title={form.prompt}>{form.prompt}</p> : null}
            {form.images.length ? (
              <div className="studio-flow-asset-gallery__images">
                {form.images.map((image, imageIndex) => (
                  <button
                    key={String(image.imageId)}
                    type="button"
                    className={image.selected ? 'is-selected' : ''}
                    aria-label={`预览${form.name}${image.name || `图片${imageIndex + 1}`}`}
                    onClick={(event) => {
                      event.stopPropagation()
                      openImagePreviewModal({
                        url: image.imageUrl,
                        title: `${assetTitle} · ${form.name}${image.name ? ` · ${image.name}` : ''}`
                      })
                    }}
                  >
                    <img src={image.imageUrl} alt={`${form.name}${image.name || `图片${imageIndex + 1}`}`} draggable={false} />
                    {image.selected ? <span>主图</span> : null}
                  </button>
                ))}
              </div>
            ) : <small>尚未生成图片</small>}
          </article>
        ))}
      </div>
    </section>
  )
}
