# 跨端备份夹具（阶段 A）

本目录存放**网页端与安卓端共用的备份兼容依据**。夹具只包含虚构资料，禁止放入任何真实用户数据、真实账号密码或真实截图。

## 当前状态

| 项 | 状态 | 说明 |
|---|---|---|
| 18 张业务表目录核对 | 已建立 | `web/tests/compat/catalog.test.ts` 直接读取下方两个事实来源，逐表逐列比对（含可空性） |
| 网页自生成的完整夹具 | 已建立 | `web/tests/compat/fixtures.ts`：18 表虚构资料、两张同长度不同字节截图、Unicode 密码 |
| 安卓生成的二进制夹具 | **未建立** | 需要运行安卓生产代码 `BackupArchive` / `BackupCrypto`，本轮按用户决定不构建、不修改安卓工程 |
| 网页输出被安卓真实恢复读回 | **未验证** | 同上，结论留空，不宣称跨端兼容完成 |

## 事实来源（只读，不复制副本）

- `app/src/main/java/com/daigan/record/backup/BackupTableCatalog.kt`
  —— 备份内的表顺序、包含关系（`included = false`）与单例标记。
- `app/schemas/com.daigan.record.data.local.RecordDatabase/8.json`
  —— Room 导出的真实列名、列顺序与 SQL 类型。
- `app/src/main/java/com/daigan/record/backup/BackupFormat.kt`、`BackupManifest.kt`、`BackupDataSet.kt`
  —— 格式标识、限额、清单字段与 `data.json` 的行数组结构。

测试同时解析 Kotlin 表目录与 schema JSON，任何一侧新增、改名或调序都会失败。这样网页端不需要维护一份会漂移的手抄列清单。

## 待补夹具（解除安卓侧限制后）

必须由安卓**生产代码**生成，不得手写 ZIP 或伪造 `data.json` 冒充安卓导出：

1. 空资料（18 表全空）备份，普通与加密各一份。
2. 18 表完整虚构资料：覆盖老板/账号（含密码与验证码登录）、模板/分类/任务/种子状态、短期与托管订单、期次、任务定义、发生记录、批量完成与撤销明细、截图引用、收款/撤销/更正、建单幂等记录、提醒设置。
3. schema 7 老结构备份（缺少 `orders.cancelledAt`、`task_definitions.monthDay`、`template_tasks.monthDay`），普通与加密各一份。
4. Unicode 密码 `代肝🔐é` 的加密备份。
5. 至少两张**字节不同**的截图（同大小不同内容用于验证哈希校验，不能只比长度）。
6. 每份夹具的规范化期望输出（表按主键排序、附件按 ID/哈希排序，排除生成器、ZIP 时间与随机盐）与附件 SHA-256。
7. `web-output/`：网页导出的普通/加密文件，供安卓侧真实恢复服务读回。

## 纪律

- 结构校验通过不等于业务校验通过：`readArchive` 只做结构与哈希校验，业务不变量由 `validateSnapshot` 强制。
- 夹具差异必须能定位到具体表/列/字节，不以“看起来一样”代替比对。
- 未验证的结论一律标注未验证，不用 JVM 级往返替代设备级恢复证据。
