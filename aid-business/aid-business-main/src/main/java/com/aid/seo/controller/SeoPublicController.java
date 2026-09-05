package com.aid.seo.controller;

import com.aid.common.annotation.Anonymous;
import com.aid.seo.model.SeoModels;
import com.aid.seo.service.SeoManagementService;
import com.aid.seo.service.PublicPageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 供搜索引擎和 Web 客户端读取的公开 SEO 资源。 */
@Anonymous
@RestController
@RequestMapping("/seo/public")
@RequiredArgsConstructor
public class SeoPublicController {
    private final SeoManagementService seoService;
    private final PublicPageService publicPageService;

    @Operation(summary = "公开详情文档", description = "网关转发原始路径，按当前发布权限生成HTML；不存在或不再公开返回404。")
    @GetMapping(value = "/page", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(@RequestHeader(value = "X-AID-Public-Path", required = false) String path) {
        String html = publicPageService.render(path);
        return ResponseEntity.status(html == null ? 404 : 200)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src https: http:; media-src https: http:; base-uri 'none'; frame-ancestors 'self'")
                .header("X-Robots-Tag", html == null ? "noindex,nofollow" : "all")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html == null ? "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>页面不存在</title><main><h1>页面不存在或已下线</h1><a href=\"/\">返回首页</a></main></html>" : html);
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(seoService.robots());
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .contentType(new MediaType("application", "xml", StandardCharsets.UTF_8))
                .body(seoService.sitemap());
    }

    @GetMapping("/meta")
    public ResponseEntity<SeoModels.MetaView> meta(@RequestParam String path) {
        SeoModels.MetaView meta = seoService.meta(path);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(meta);
    }
}
