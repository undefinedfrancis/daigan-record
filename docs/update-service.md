# 更新服务

`update-service/` 是 `com.daigan.record` 的 v1 更新接口，现已改为**不可变发布库**：`/v1/check` 的版本、大小、SHA-256、签名信息全部由发布工具从同一个 APK 生成，不再使用独立的哈希环境变量。

接口契约（已安装 1.8.3 可直接解析）：

- `GET /v1/check` → `packageName`、`versionCode`、`versionName`、`notes`、`sizeBytes`、`sha256`、`downloadUrl`
- `GET /v1/download/<releaseId>` → 固定版本的 APK
- `GET /v1/download` → 当前激活版本（兼容旧地址）

Android 构建时通过 Gradle 属性注入地址：

```sh
./gradlew :app:assembleRelease -PupdateServiceUrl=https://your-domain.example
```

发布与激活流程（暂存 → 上传 → 回下载校验 → 原子切换指针；失败保留上一版）见 `update-service/README.md`。激活需要先把 APK 放到版本化 HTTPS 地址并通过 `--artifact-url` 校验；本次未上传、未部署、未切换线上元数据。

未配置 `updateServiceUrl` 时，应用不会声称已有更新服务。
