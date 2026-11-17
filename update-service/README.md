# 更新服务（v1 接口 + 不可变发布库）

服务只做两件事：`/v1/check` 返回**一个已激活发布记录**的元数据，`/v1/download/...` 提供该记录绑定的**同一个 APK**。版本号、大小、SHA-256、签名全部由发布工具从 APK 读取，不再有独立维护的 `RELEASE_SHA256`。

## 与已安装 1.8.3 的兼容

| 接口 | 说明 |
|---|---|
| `GET /v1/check` | 返回 `packageName`、`versionCode`、`versionName`、`notes`、`sizeBytes`、`sha256`、`downloadUrl`；字段与 1.8.3 客户端解析的契约一致，新增字段（`releaseId`、`signerSha256`）旧客户端会忽略 |
| `GET /v1/download/<releaseId>` | `/v1/check` 现在返回的固定版本下载地址；同一 `releaseId` 的字节永不改变 |
| `GET /v1/download` | 仍返回当前激活版本，供缓存了旧地址的客户端使用 |

`downloadUrl` 是相对地址，客户端按 `baseUrl + downloadUrl` 解析，因此版本化路径不需要客户端改动。

## 发布库结构

```
<store>/latest.json                             激活指针（唯一可变文件）
<store>/releases/<versionCode>-<versionName>/release.json
<store>/releases/<versionCode>-<versionName>/<apk>       可选：本地托管的同一 APK
```

`release.json` 记录 `versionCode`、`versionName`、`sizeBytes`、`sha256`、`signer.sha256/subject/schemes`、`artifactFile`/`artifactUrl`、`immutable: true`。记录一旦写入不可覆盖。

发布库根目录通过 `RELEASE_STORE_DIR` 指定，默认 `update-service/releases`。包名通过 `APP_PACKAGE` 覆盖，默认 `com.daigan.record`。

## 发布流程

```sh
# 1) 暂存：只写 release.json 与 APK 副本，不动物流指针
node tools/publish-release.mjs \
  --apk ../output/releases/v1.8.4-candidate/daigan-record-v1.8.4-candidate.apk \
  --notes "1.8.4 手机验收候选" \
  --stage-only

# 2) 激活：先把同一 APK 上传到版本化地址，再回下载校验
node tools/publish-release.mjs \
  --apk ../output/releases/v1.8.4-candidate/daigan-record-v1.8.4-candidate.apk \
  --notes "1.8.4 手机验收候选" \
  --artifact-url https://<静态托管>/v1.8.4/daigan-record-v1.8.4.apk
```

激活顺序（任一步失败都不会切换 `latest.json`，上一版本继续服务）：

1. 用 `aapt2`/`apksigner` 从 APK 读取包名、`versionCode`、`versionName`、签名证书；
2. 计算大小与 SHA-256，写入 `releases/<releaseId>/release.json` 并复制 APK；
3. 重新读取暂存副本，比对大小与 SHA-256；
4. 流式下载 `--artifact-url`，再次比对大小与 SHA-256；
5. 原子重命名写入 `latest.json`。

安全闸门：

- `--stage-only`：只暂存，不激活；
- 未提供 `--artifact-url` 时默认拒绝激活（可用 `--allow-local-artifact` 表示自托管、由本服务自己提供字节）；
- `--expected-signer-sha256`：要求签名证书摘要匹配，防止换签；
- `--allow-downgrade`、`--allow-same-version`：默认拒绝降级和同版本不同字节；
- 激活目标记录或 APK 校验失败时，`/v1/check` 自动回退到 `previousReleaseId` 并带 `x-release-degraded: true`。

## 本地运行与测试

```sh
cd update-service
node --test test/          # 发布/校验/回退/接口契约
PORT=8787 node server.mjs  # 本地起服务，本地库默认 update-service/releases
```

本地服务同时接受 `/v1/...` 与 `/api/v1/...`，便于对照部署路径。

## 部署与激活所需的凭据/配置（本次未执行）

以下步骤都未在本次任务中执行，没有任何线上切换：

1. 由一个 HTTPS 静态源/对象存储托管版本化 APK，地址形如 `https://<host>/v1.8.4/daigan-record-v1.8.4.apk`，且**不可覆盖同名文件**；
2. 把 `releases/**`（`release.json` 与 `latest.json`）随函数部署；若发布库放在别处，设置 `RELEASE_STORE_DIR`；
3. 平台要求：`vercel.json` 已声明 `includeFiles: "releases/**"` 与 `/api/v1/download/:releaseId` 重写；函数响应体有大小上限，线上必须使用 `artifactUrl` 让下载走 302 跳转，而不是由函数直接吐 APK；
4. 不再需要 `PGYER_API_KEY` / `PGYER_APP_KEY` / `RELEASE_SHA256`。删除这些环境变量属于线上变更，需人工确认后执行；
5. 首次激活在完成第 1～2 步之后用上面的“激活”命令进行，并确认返回的 `sha256` 与候选包一致。

## 与旧实现的区别

旧实现每次请求实时读取上游最新安装包，哈希来自独立环境变量，曾出现“1.8.3 版本配 1.8.1 哈希”。新实现把版本/大小/哈希/签名绑定到同一个不可变 APK，校验失败时旧版本保持激活，不再可能把不匹配的哈希发给客户端。

## 蒲公英 + Vercel 正式发布（2026-10-21）

正式服务为 `https://update.undefinedyou.com`，兼容已安装的 1.8.3。
更新信息来自不可变发布记录，下载地址包含 releaseId；每次下载按固定
`pgyerBuildKey` 获取源文件，由 Vercel 流式传输给设备，避免设备直连实测过慢的海外 CDN。API Key 只在 Vercel 使用，不返回手机。
不再读取 `RELEASE_SHA256`，无需逐版本修改环境变量。

以后发布分两步：

1. 用现有签名打包，提高 Android versionCode，将**同一份最终 APK**上传蒲公英。
2. 在此目录运行（需要 Android SDK 和已登录的 Vercel CLI）：

```sh
ANDROID_HOME=/Users/francis/Library/Android/sdk npm run release:pgyer -- /absolute/path/final.apk
```

命令核对本地签名和蒲公英版本，暂存固定构建记录，部署隔离候选。
Vercel 构建时回下载 APK，完整比对大小和 SHA-256，失败则不切换正式域名。
成功后切换域名并从正式地址再次下载比对；失败自动恢复上次成功部署。
本机 CLI 路径保存在忽略提交的 `.vercel/cli-path`，也可用 `VERCEL_CLI` 指定。
`.vercel/last-success.json` 保存成功部署作为回退点，务必保留。

发布记录保留旧版本，避免新上传影响正在下载的旧版本。
不要删除蒲公英中已经发布并仍可能被用户下载的构建。
旧版 App 缓存的 `/v1/download` 仍可用；新的检查返回 `/v1/download/<releaseId>`。
手机负责最终的包名、版本和签名校验并调用 Android 安装器。

2026-10-21 已上线 1.8.4（versionCode 13），固定蒲公英构建
`86bec221454c94db7c40bb21d1582614`。
后续新版本尚需实际执行一次发布命令验证完整跨版本流程；当前不声称已完成未来版本的手机升级实测。
