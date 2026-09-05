# 受管 Nginx 配置

后台「项目升级 → Nginx 网关」管理本安装的公共入口与管理入口。应用进程不需要 root 权限或 Docker Socket；由已部署的升级器执行固定操作。所有操作需要独立权限 `aidconfig:upgrade:nginx`，并写入操作审计与升级任务记录。

## 配置来源

Docker 使用现有 `.env`，原生部署使用现有 `aid-deploy.conf`。以下值可在后台编辑：

| 配置键 | 默认值 | 范围 |
| --- | --- | --- |
| NGINX_BACKEND_ORIGIN | Docker：`http://aid-server:8080`；原生：本机后端端口 | HTTP(S) 主机及可选端口，不带路径或凭证 |
| NGINX_MAX_BODY_MB | 1024 | 1–10240 |
| NGINX_READ_TIMEOUT_SECONDS | 300 | 1–3600 |
| NGINX_CONNECT_TIMEOUT_SECONDS | 10 | 1–120 |
| NGINX_EXTRA_DIRECTIVES | 空 | gzip、gzip_min_length、keepalive_timeout、client_body_timeout、send_timeout；单行分号分隔 |

分机部署填写 **Nginx 运行环境可访问的后端源地址**，例如 `https://api.example.com`。不要填写 Web 地址导致循环代理，也不要附加 `/aid`。HTTPS 上游启用 SNI 和证书验证；私有 CA 应由运维安装到网关信任库，不提供关闭验证的开关。

在线操作只作用于当前升级器所在安装，不通过 SSH 修改另一台机器。前端若由外部独立网关托管，应在那台网关按下方说明配置代理；改变后端地址本身不会建立跨机管理通道，也不会同步账号和认证配置。

`config/nginx-managed/public.conf` 和 `admin.conf` 是从这些参数生成的文件。不要直接手工改生成文件：升级会保留参数并更新模板。Docker 将整个目录挂载到 `/etc/nginx/aid-managed`，模板目录以只读形式挂载。不要改回单文件挂载。

## 生效与恢复

「仅校验」渲染候选文件并执行 `nginx -t`，不保存或重载。「校验并应用」再次检查当前指纹，备份原配置，校验完整站点后持久化参数并平滑重载。失败尝试恢复原配置；恢复未成功时保留中断记录，升级器在继续任务前重试恢复。最后一次成功应用前的快照用于手动回退，回退只影响 Nginx 参数，不回退数据库连接等其他部署值。

校验还会从网关网络请求候选后端的 `/seo/public/robots.txt`，后端不可达时拒绝应用；此检查不等同于业务验收。应用后应检查后台任务结果，并使用真实域名访问 `/robots.txt`、`/sitemap.xml`、一个已公开详情和一个不存在的路径；期望类型分别为纯文本、XML、HTML 200 和 404。定时提交成功也不等于搜索引擎已经收录。

旧部署需要先升级升级器和完整部署包，重建网关挂载；只替换 JAR 或 Web 静态文件不会安装受管能力。旧版升级器不会宣告本能力，后台将禁用编辑。卸载沿用已有数据保留选项；不要将包含备份的配置目录对外作为静态目录开放。

## 第三方自管 Nginx

不自动写入宝塔、外部网关或其他站点。运维可在站点的 server 块采用下面的等价配置，并替换后端地址。静态页面应使用各自生成文件，不能统一返回首页；动态公开页面仅由服务端当前公开权限决定。

```nginx
location = /robots.txt {
    proxy_pass https://api.example.com/seo/public/robots.txt;
    proxy_ssl_server_name on;
    proxy_ssl_verify on;
    proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
}
location = /sitemap.xml {
    proxy_pass https://api.example.com/seo/public/sitemap.xml;
    proxy_ssl_server_name on;
    proxy_ssl_verify on;
    proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
}
location / { try_files $uri $uri/ @aid_public_page; }
location ~ /__static__(/|$) { return 404; }
location @aid_public_page {
    rewrite ^ /seo/public/page? break;
    proxy_pass https://api.example.com;
    proxy_set_header X-AID-Public-Path $request_uri;
    proxy_ssl_server_name on;
    proxy_ssl_verify on;
    proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
}
```

保存前备份站点文件，执行 `nginx -t` 成功后再 reload。上面的配置不替代站点原有 API、TLS、上传、缓存及访问控制规则。
