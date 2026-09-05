package com.aid.seo.service;

import com.aid.seo.model.SeoModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** 为静态部署生成具有真实状态码和首屏内容的公开详情文档。 */
@Service
@RequiredArgsConstructor
public class PublicPageService {
    private final List<PublicPageResolver> resolvers;
    private final SeoManagementService seoService;

    public String render(String requestPath) {
        String path = normalize(requestPath);
        if (Objects.isNull(path)) return null;
        // The registry is not authorization: every request rechecks the source publication state.
        SeoModels.PublicDocument document = null;
        for (PublicPageResolver resolver : resolvers) {
            document = resolver.resolvePublicPage(path);
            if (Objects.nonNull(document)) break;
        }
        if (Objects.isNull(document)) return null;
        SeoModels.Settings settings = seoService.getSettings();
        String origin = safeUrl(settings.getSiteUrl());
        if (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
        SeoModels.MetaView meta = seoService.meta(path);
        String title = fallback(Objects.isNull(meta) ? null : meta.getTitle(), document.getTitle());
        String description = fallback(Objects.isNull(meta) ? null : meta.getDescription(), document.getDescription());
        String keywords = fallback(Objects.isNull(meta) ? null : meta.getKeywords(), document.getKeywords());
        String robots = Objects.isNull(meta) ? "index,follow" : meta.getRobots();
        String image = safeUrl(document.getImageUrl());
        String video = safeUrl(document.getVideoUrl());
        String interactive = Objects.toString(document.getInteractivePath(), "");
        if (!interactive.matches("/[a-zA-Z0-9/_-]+\\?id=[1-9][0-9]*")) interactive = "";
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + escape(title) + "</title>"
                + tag("description", description) + tag("keywords", keywords) + tag("robots", robots)
                + (origin.isEmpty() ? "" : "<link rel=\"canonical\" href=\"" + escape(origin + path) + "\">")
                + "<meta property=\"og:type\" content=\"article\"><meta property=\"og:title\" content=\"" + escape(title) + "\">"
                + "<meta property=\"og:description\" content=\"" + escape(description) + "\">"
                + (image.isEmpty() ? "" : "<meta property=\"og:image\" content=\"" + escape(image) + "\">")
                + "<style>body{margin:0;background:#f6f7fb;color:#202635;font:16px/1.8 system-ui,sans-serif}"
                + "main{max-width:960px;margin:auto;padding:28px 20px 64px}nav{margin-bottom:32px}a{color:#465ad2}"
                + "article{background:white;border:1px solid #e4e7ef;border-radius:20px;padding:clamp(20px,5vw,48px)}"
                + "h1{font-size:clamp(26px,4vw,42px);line-height:1.3;overflow-wrap:anywhere}p{white-space:pre-wrap;overflow-wrap:anywhere}"
                + "img,video{display:block;width:100%;max-height:640px;object-fit:contain;border-radius:12px;background:#eef0f5;margin:24px 0}"
                + ".action{display:inline-block;padding:10px 20px;border-radius:10px;background:#465ad2;color:white;text-decoration:none}</style>"
                + "</head><body><main><nav><a href=\"/\">" + escape(fallback(settings.getSiteName(), "首页")) + "</a></nav><article>"
                + "<h1>" + escape(document.getTitle()) + "</h1><p>" + escape(document.getDescription()) + "</p>"
                + (image.isEmpty() ? "" : "<img src=\"" + escape(image) + "\" alt=\"" + escape(document.getTitle()) + "\">")
                + (video.isEmpty() ? "" : "<video controls preload=\"metadata\" src=\"" + escape(video) + "\"></video>")
                + (Objects.equals(document.getDescription(), document.getText()) ? "" : "<p>" + escape(document.getText()) + "</p>")
                + (interactive.isEmpty() ? "" : "<a class=\"action\" href=\"" + escape(interactive) + "\">打开完整作品预览</a>")
                + "</article></main></body></html>";
    }

    private String normalize(String value) {
        if (Objects.isNull(value) || value.length() > 1024 || !value.startsWith("/") || value.startsWith("//")) return null;
        try {
            URI uri = URI.create(value);
            String path = uri.getPath().replaceAll("/+$", "");
            return path.matches("/[a-zA-Z0-9_-]+/[1-9][0-9]{0,17}") ? path : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    private String safeUrl(String value) {
        if (Objects.isNull(value) || value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            return (Objects.equals(uri.getScheme(), "https") || Objects.equals(uri.getScheme(), "http"))
                    && Objects.nonNull(uri.getHost()) && Objects.isNull(uri.getUserInfo()) ? value : "";
        } catch (IllegalArgumentException ex) { return ""; }
    }

    private String escape(String value) { return HtmlUtils.htmlEscape(Objects.toString(value, "")); }
    private String fallback(String value, String other) { return Objects.isNull(value) || value.isBlank() ? other : value; }
    private String tag(String name, String value) { return "<meta name=\"" + name + "\" content=\"" + escape(value) + "\">"; }
}
