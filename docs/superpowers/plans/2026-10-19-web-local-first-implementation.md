# 代肝记录本地优先网页端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付无需注册、可离线使用、完整覆盖安卓 v1.6 业务并双向交换 `.dgbk` 备份的响应式网页账本。

**Architecture:** 在现有仓库新增独立 `web/` 应用；React 页面调用业务服务，业务服务通过事务接口操作 Dexie，截图 Blob 与业务资料同库。备份协议独立于网页内部数据库版本，恢复先验证候选数据库，再原子切换活动指针；跨标签页锁保护命令、导出和资料切换。

**Tech Stack:** React、TypeScript、Vite、Dexie、shadcn/ui、Web Crypto、vite-plugin-pwa；Vitest、fake-indexeddb、Testing Library、Playwright。包版本在任务 1 核验并锁定；压缩库使用支持 Worker 和流式限额解包的 `@zip.js/zip.js`，实施前核验其当前 API 与兼容性。

**Spec:** `docs/superpowers/specs/2026-10-19-web-local-first-design.md`（已获用户确认）。

## Global Constraints

- 第一版无需注册，无业务云端服务，两端分别保存资料，通过通用 `.dgbk` 备份文件手动接续使用。
- 手动导入为整份替换，不合并两个设备分别编辑的资料。
- 完整覆盖安卓 v1.6 业务；网页提醒仅应用内提示，安卓三类提醒配置只读保留，不申请通知权限。
- 接受 schema 7、8 的普通/加密备份；输出格式 v1、schema 8；18 张业务表，排除本机状态。
- `DAIGANBK`、PBKDF2-HMAC-SHA256（210000 次）、16 字节盐、12 字节 nonce、AES-256-GCM、128 位标签与安卓一致。
- 归档 512 MiB、JSON 64 MiB、清单 1 MiB、2000 个附件、单图 32 MiB、附件总计 384 MiB、5000 条目；JSON 最大深度 32；解压超过 8 MiB 后压缩比上限 200。
- 金额安全整数分，精确小数文本、epoch 毫秒、ISO 日期与原始业务/操作 ID 保留；不存在的月日跳过。
- 先写入完整候选资料，活动指针控制事务为恢复唯一提交点；不清库兜底，不允许旧表单跨资料写入。
- 主要触控目标至少 44px；沿用奶油白、鼠尾草绿手账视觉；所有核心资源离线可用。
- 不将密码、截图或业务数据发到网络、URL、分析服务或 Service Worker 业务缓存；备份加密不代表本地数据库加密。
- 不修改安卓现有业务行为、历史 schema 1–8 或备份协议；只允许新增测试入口和中立夹具。
- 所有任务按失败测试→确认失败原因→最小实现→验证通过→检查差异→定向提交执行；不将缺少工具链造成的失败算作行为测试失败。

## Review Focus

1. JSON 中超过 JS 安全整数的值必须在失真前拒绝，不能由 `JSON.parse` 四舍五入后通过校验（任务 2）。
2. 单文件损坏、候选资料完整但指针提交瞬间关页，重开后必须得到完整旧或新资料（任务 5）。
3. 两个标签页先后完成收款/恢复，旧页面排队的命令不能写入新账本（任务 4、5、8）。
4. 浏览器只发起下载或用户取消保存时，不应显示“备份已安全保存”，也不能改变当前资料（任务 12）。
5. 应用升级遇到另一标签页、未保存表单或正在导出时，应等待且明确反馈，不能强制刷新或清库（任务 13）。

## 执行准备、文件边界与公共类型

执行前使用 `superpowers:using-git-worktrees` 确认隔离工作区；优先检查现有附件并复用合适工作区。不得把当前 `docs/user-feedback-issues.md` 的用户修改及 `output/` 混入实现提交。读取设计及任务引用的安卓实现，不单凭旧数据模型文档重写规则。

`web/package.json` 固定提供：`dev`、`build`、`typecheck`、`lint`、`test`（`vitest run`）、`test:e2e`（`playwright test`）。以下网页命令均在 `web/` 执行；Android 命令在仓库根目录执行，按 `docs/upstream-foundation.md` 使用本机 JDK 21 与现有 SDK，不自行升级工具链。

| 路径 | 职责和约束 |
|---|---|
| `compat/backup/` | 仅虚构资料、schema7/8 备份、预期规范化输出、附件哈希及夹具来源；测试密码公开 |
| `web/src/contracts/` | 备份表类型、标准资料、错误类型；不依赖 UI |
| `web/src/backup/` | 归档、信封、校验和导入导出协调，各文件单一职责 |
| `web/src/domain/` | 时间、金额、周期、状态与财务纯函数 |
| `web/src/storage/` | 业务数据库、控制数据库、锁、资料身份及迁移 |
| `web/src/application/` | 业务命令、只读查询和事务边界 |
| `web/src/features/` | today、accounts、orders、ledger、templates、settings 页面 |
| `web/src/platform/` | 文件访问、容量检查、PWA 更新与离线资源 |
| `web/tests/` | browser、e2e、compat；测试夹具不得包含真实用户资料 |
| `docs/handoffs/web-local-first.md` | 每阶段证据、浏览器边界、未验证项和交付说明 |

任务 1 定义 `TableName`（18 表字面量联合）、`TableRows`（各表命名字段的精确类型映射）、`BackupDataSet = { [K in TableName]: TableRows[K][] }`、`Snapshot = { data: BackupDataSet; attachments: Map<string, Blob> }`（附件 ID 为键）、`SchemaVersion = 7 | 8`。内部命名字段和备份列数组之间只能用明确适配器转换。

任务 4 定义 `DatasetToken = { databaseName: string; generation: number }`、`ReadContext = { token: DatasetToken; snapshot: Snapshot }`、`WriteContext = { token: DatasetToken; tx: BusinessTransaction }`；`BusinessTransaction` 暴露有类型的表访问，禁止向页面暴露它。

所有业务服务由 `createServices(runtime: DatasetRuntime, clock: () => number, newId: () => string): Services` 组合，命令参数额外携带表单打开时的 `DatasetToken`。每个任务在 `web/src/application/contracts.ts` 扩展 `Services`，接口的输入校验与安卓对应 Draft/命令一致；任务中明确新增的字段如下。冲突以 `AppError.code` 区分，不靠界面文案判断。

## Task 1: 可执行网页测试底座与安卓兼容夹具

**Files:** 新增 `web/package.json`、`web/package-lock.json`、`web/tsconfig.json`、`web/vite.config.ts`、`web/vitest.config.ts`、`web/playwright.config.ts`、`web/eslint.config.js`、`web/index.html`、`web/src/main.tsx`、`web/src/contracts/{tables,backup,errors}.ts`、`web/tests/compat/catalog.test.ts`、`compat/backup/README.md`；新增 `app/src/test/java/com/daigan/record/backup/PortableBackupFixtureTest.kt` 及 `compat/backup/` 夹具。`.gitignore` 只追加网页生成产物。

**Interfaces:** 产出上述公共备份类型、`AppError { code: string; message: string }`，以及 schema7/8 的表列目录 JSON；目录从 `BackupTableCatalog` 和真实 schema 提取并交叉核对，禁止手工猜字段。夹具包含空资料、18 表完整虚构资料、普通/加密、schema7/8、Unicode 密码和至少两张不同字节截图。

- [ ] Step 1：先核验运行时及官方依赖 API，锁定版本；搭起最小测试入口。增加 `catalog_matches_android` 与 Kotlin 夹具测试，断言业务表数为 18、保留 `cancelledAt/monthDay`、排除两张本机表；运行 `npm test -- tests/compat/catalog.test.ts`，确认缺少目录/类型导致失败。
- [ ] Step 2：实现表类型和仅测试用夹具生成器。使用生产 `BackupArchive`、`BackupCrypto` 导出备份，附预期规范化资料与 SHA-256，公开密码 `代肝🔐é`；不得用手写 ZIP 冒充安卓导出。schema7 使用真实旧列集合并匹配清单版本。
- [ ] Step 3：运行 `./gradlew :app:testDebugUnitTest --tests '*PortableBackupFixtureTest'` 与网页目录测试；预期均通过且夹具重复生成的规范化结果一致。再运行 `npm run typecheck`、`npm run build`；只建立最小空页面，不先写业务。
- [ ] Step 4：提交本任务精确文件，提交说明 `test: establish web harness and Android backup fixtures`。记录依赖选择与许可来源。

## Task 2: 标准数据、密码信封与有限额归档编解码

**Files:** 新增 `web/src/backup/{catalog,dataCodec,crypto,archive,limits,worker}.ts`、`web/tests/compat/{dataCodec,crypto,archive}.test.ts`。

**Interfaces:** `decodeData(text: string, schema: SchemaVersion): BackupDataSet`；`encodeData(data: BackupDataSet): string`；`readArchive(file: Blob, password?: string): Promise<Snapshot>`；`writeArchive(snapshot: Snapshot, password?: string): Promise<Blob>`。`readArchive` 此阶段完成结构/哈希验证，业务验证由任务 3 调用方强制执行。输出 manifest 的字段与生成器格式对齐安卓 `BackupManifest.kt`，不新增安卓不认识的顶层字段。

- [ ] Step 1：写 `dataCodec` 失败测试：`expect(decodeData(schema7, 7).orders[0].cancelledAt).toBeNull()`；原有列逐值不变；超出安全整数、非整数、布尔冒充 0/1、深度 33、漏表/重复表/错列失败。运行 `npm test -- tests/compat/dataCodec.test.ts`，确认目标断言失败。
- [ ] Step 2：实现严格类型和整数词法检查后再解析，拒绝失真；只允许现行 7→8 的三个 NULL 补列；小数进度保持字符串。与安卓对照有效输入，不能擅自归一化日期或小数。
- [ ] Step 3：写 crypto/archive 失败测试：安卓加密夹具可解密，密码错/截断/篡改/未知版本拒绝；盐 16、nonce 12、迭代 210000。对每个字节限制测试“等于上限可进入下一层、上限+1 拒绝”，并覆盖路径穿越、重复条目、清单遗漏附件、哈希/字节数错误和超压缩比。运行 `npm test -- tests/compat`，确认新行为尚未实现。
- [ ] Step 4：使用 Web Crypto 和流式解包实现接口，Worker 执行压缩/哈希任务；解密认证成功后才解包。输入和实际解压字节均计数，取消终止 Worker 并清理会话，不持久化密码。为测试注入较低限额，避免单元测试实际分配 512 MiB。
- [ ] Step 5：运行 `npm test -- tests/compat`、`npm run typecheck`，预期通过；向 `compat/backup/web-output/` 生成网页普通/加密输出，新增 Kotlin `PortableBackupFixtureTest` 读回断言原列与附件哈希一致。运行其 Gradle 测试，预期通过；提交 `feat: implement portable backup codec and encryption`。

## Task 3: 导入业务校验器与跨端资料往返门槛

**Files:** 新增 `web/src/backup/{validateSnapshot,normalize}.ts`、`web/tests/compat/validation.test.ts`、`app/src/androidTest/java/com/daigan/record/backup/PortableBackupRestoreTest.kt`；更新 `compat/backup/README.md`。

**Interfaces:** `validateSnapshot(snapshot: Snapshot): void`（失败抛 `AppError`）；`normalizeSnapshot(snapshot: Snapshot): Promise<string>`（表按主键排序、附件按 ID/hash 排序，排除生成器/ZIP 时间/盐）；`ValidatedSnapshot` 由本模块构造并封装，后续候选数据库只接收该类型。

- [ ] Step 1：写失败测试，遍历安卓 `BackupValidator.kt` 的枚举、外键、单例、唯一约束、金额、账号密码类型、任务与期次归属和附件双归属检查；将一张截图换成同大小不同字节必须失败。运行 `npm test -- tests/compat/validation.test.ts` 确认失败。
- [ ] Step 2：实现校验并产出 `ValidatedSnapshot`；结构层与业务层错误明确区分，避免漏调业务校验即恢复。覆盖空账本缺少可选单例时的安卓既有语义。
- [ ] Step 3：真实 Room 测试使用生产恢复服务读取网页输出；安卓重新导出，再由网页规范化比对。反向由网页创建虚构资料输出→安卓恢复/导出→网页读取；schema7 输入只比较升级后三个新字段为 NULL 的预期。
- [ ] Step 4：运行 `npm test -- tests/compat`；运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.backup.PortableBackupRestoreTest`，预期真实数据库恢复、导出、提醒偏好和字节哈希全部通过。无设备时记录阻塞，不宣布跨端兼容完成。
- [ ] Step 5：提交 `test: verify bidirectional backups with real Android restore`。任务 3 未通过不得把网页导出标为可供用户正式使用。

## Task 4: 活动资料、本地事务与多标签页一致性

**Files:** 新增 `web/src/storage/{businessDb,controlDb,datasetRuntime,locks,migrations}.ts`、`web/src/application/contracts.ts`、`web/tests/browser/datasetRuntime.spec.ts`、`web/src/storage/datasetRuntime.test.ts`。

**Interfaces:** `DatasetRuntime.read<T>(fn: (ctx: ReadContext) => Promise<T>): Promise<T>`；`write<T>(expected: DatasetToken, fn: (ctx: WriteContext) => Promise<T>): Promise<T>`；`exclusive<T>(fn: () => Promise<T>): Promise<T>`；`current(): Promise<DatasetToken>`。底层同源单一 Web Lock 名称 `daigan-dataset`，所有操作以排他方式串行，避免嵌套拿锁；内部同锁操作使用私有上下文。

- [ ] Step 1：写失败测试，断言创建空资料具有本机新身份，18 表+Blob 可持久化；事务第二步失败第一步回滚；两个标签页重复编号只成功一次。运行 `npm test -- src/storage/datasetRuntime.test.ts` 与 `npm run test:e2e -- tests/browser/datasetRuntime.spec.ts` 确认失败。
- [ ] Step 2：实现控制库 `activeDataset`、`sessions`、`localPreferences`，业务库标准表/索引及 `attachmentBlobs`。迁移纯增量、失败保留；每次读写拿锁后复查代次。`ReadContext` 从同一快照构造，统计不混读不同事务。
- [ ] Step 3：实现锁能力检测、数据库连接关闭及广播通知；缺少锁能力不启用业务写入。真实浏览器测试后台标签页广播丢失、两个页面同时分配老板编号、旧代次写入被拒绝。
- [ ] Step 4：重跑上述两条命令，预期 PASS；验证页面代码不能导入 `businessDb`（ESLint import 限制）。提交 `feat: add guarded local dataset transactions`。

## Task 5: 整体恢复与一致快照导出

**Files:** 新增 `web/src/backup/{restoreService,exportService}.ts`、`web/src/storage/recovery.ts`、`web/tests/browser/restoreRecovery.spec.ts`、`web/src/backup/restoreService.test.ts`。

**Interfaces:** `prepareRestore(file: Blob, password?: string): Promise<RestorePreview>`；`commitRestore(id: string, expected: DatasetToken): Promise<DatasetToken>`；`cancelRestore(id: string): Promise<void>`；`exportCurrent(password?: string): Promise<{ blob: Blob; token: DatasetToken }>`；`recover(): Promise<DatasetToken>`。`RestorePreview` 包括候选 ID、基准 token、生成器/时间、各表数量、ACTIVE 实收、有效待收、附件数量和字节数。

- [ ] Step 1：写失败测试：准备中原资料不变；坏文件无候选激活；预览后业务变更应拒绝旧确认；批次内附件事务失败不留下半份 READY。运行 `npm test -- src/backup/restoreService.test.ts` 确认失败。
- [ ] Step 2：实现控制会话 PREPARING→READY→ACTIVE；候选内容与 READY 标记同库完成后，锁内检查候选及原 token，在控制库事务切换活动指针/代次。所有写入（含设置）递增代次，旧表单和过期确认收到 `STALE_DATASET`。只在恢复启动/维护锁内清理会话。
- [ ] Step 3：写真实浏览器失败测试，在候选填充中、READY 后、指针事务前/后、删除旧库前/后关闭页面再打开，断言完整旧或新资料；控制库指向不存在数据库时显示可恢复错误，不新建空账本。加入容量不足、旧库删除 blocked、并行预览/取消各自只清自己会话。
- [ ] Step 4：实现恢复屏障、故障注入测试入口及导出锁内一致快照；释放锁后打包。故障注入仅测试构建可用；浏览器进程级终止另设专用运行，不以页面关闭代替真实进程死亡证据。
- [ ] Step 5：运行 `npm test -- src/backup/restoreService.test.ts` 和 `npm run test:e2e -- tests/browser/restoreRecovery.spec.ts`，预期通过；提交 `feat: restore local datasets through atomic activation`。

## Task 6: 金额、游戏日历、发生槽与财务纯规则

**Files:** 新增 `web/src/domain/{money,decimal,gameCalendar,occurrences,ledger}.ts`、同目录 `.test.ts`；新增 `compat/rules/{calendar,ledger}.json`。

**Interfaces:** `parseMoney(text: string): number`；`gameDateAt(epochMs: number, zoneId: string, refreshMinutes: number): string`；`occurrencesFor(task: TableRows['task_definitions'], period: TableRows['hosting_periods'], date: string): OccurrenceSlot[]`；`ledgerFor(data: BackupDataSet, report: { month: string; zoneId: string }): LedgerSummary`。`OccurrenceSlot` 固定 `{ key: string; gameDate: string; windowStart: string | null; windowEnd: string | null }`；Summary 包括 `receivedCents`、`outstandingCents`、`orderCount`、按订单/期次待收列表。

- [ ] Step 1：从现有 `MoneyTest`、`GameCalendarTest`、`OccurrenceRulesTest` 和财务测试提取中立输入/输出；写失败断言：0.10+0.20=30 分、超上限拒绝、纽约 DST、每周独立刷新、首尾包含、2 月无 31 日为空、不把补录算今日；运行 `npm test -- src/domain` 确认失败。
- [ ] Step 2：实现纯函数；小数进度使用精确十进制运算（不经过二进制浮点），日期/时区调用锁定的成熟库并逐例与安卓对齐，不手写 DST 偏移。
- [ ] Step 3：增加流水 AMEND/VOID、续期旧欠款、作废保留实收但退出待收、报表月半开区间、订单按 createdAt 和实收按 receivedAt 的断言；运行 `npm test -- src/domain`，预期全部通过，提交 `feat: port calendar and financial invariants`。

## Task 7: 老板、账号和模板命令

**Files:** 新增 `web/src/application/{accounts,templates}.ts`、相应 `.test.ts`、`web/src/assets/templates/generic.json`、`web/src/backup/templateCodec.ts`；扩展 `application/contracts.ts`。

**Interfaces:** `Services.accounts.saveOwner(input: { id?: string; nickname: string }, token): Promise<string>`；`saveAccount(input: AccountDraft, token): Promise<string>`；`archive(kind: 'owner' | 'account', id: string, token): Promise<void>`；`restore(kind, id, token): Promise<void>`。`AccountDraft` 为账号表可编辑字段（ownerId/gameName/server/uid/loginAccount/loginMethod/password/defaultTemplateId），可选 id。模板 `save(input: TemplateDraft, token): Promise<string>`、`remove(id, token)`、`importFile(file: Blob, mode: 'copy' | 'replace', token)`、`exportFile(id): Promise<Blob>`；`TemplateDraft` 明确包含模板可编辑字段和分类/任务数组，剔除 createdAt/updatedAt/origin/builtInKey 等由服务产生的字段。

- [ ] Step 1：写失败测试：昵称边界、验证码登录密码 NULL、编号不回收、归档守卫、按批次恢复、模板修改不影响订单快照、删内置不重生、重复初始化不覆盖用户编辑；运行 `npm test -- src/application/accounts.test.ts src/application/templates.test.ts` 确认失败。
- [ ] Step 2：实现命令与只读查询；模板文件沿用 `docs/template-file-format.md` 及安卓编解码的结构、大小限制、ID 重映射和替换语义。将内置通用模板从现有资产复制并测试内容一致，不引入远程模板源。
- [ ] Step 3：加入非法模板零写入、同名另存、默认模板删除清理、旧账号无需模板的断言，重跑上述命令预期通过；提交 `feat: add local accounts and template workflows`。

## Task 8: 建单、续期、纠错与收款命令

**Files:** 新增 `web/src/application/{orders,receipts,commandFingerprint}.ts`、相应 `.test.ts`；扩展 `application/contracts.ts`。

**Interfaces:** `Services.orders.create(input: CreateOrderCommand, token): Promise<{ orderId: string; receiptId: string | null }>`；`renew(input: RenewCommand, token): Promise<string>`；`changeDates(input: { orderId: string; periodId?: string; startDate?: string; endDate?: string; deadlineDate?: string | null }, token)`；`cancel(id, token)`；`deleteEmpty(id, token)`。Create/Renew 命令在同目录 contracts 显式定义：包含 operationId、账号/订单 ID、标题/价格/付款方式、对应日期、游戏日历快照、任务草稿、可选首笔收款；字段范围照安卓 `OrderDrafts.kt` 和仓储命令，不接受实体整行覆盖。`Services.receipts.record(input: ReceiptCommand, token)`、`amend(input: AmendCommand, token)`、`void(input: VoidCommand, token)` 返回持久化操作结果，均含 operationId，原因参与指纹。

- [ ] Step 1：写失败测试：同一建单 operationId 重试返回原订单及首笔收款，改载荷拒绝；并行重复收款只增一行；金额越界/超应收拒绝，续期不重写旧流水；运行 `npm test -- src/application/orders.test.ts src/application/receipts.test.ts` 确认失败。
- [ ] Step 2：实现单事务命令和可重建的指纹；从安卓 `OrderCommandFingerprintTest` 导出共同向量，确保跨端导入后的命令仍正确识别重试，不新造历史 operationId。
- [ ] Step 3：增加日期缩短排除历史记录/期次重叠拒绝、清除短期截止、空单删除、有截图/续期/收款引用只能作废、AMEND 原行留存、旧 token 排队收款被拒绝断言；重跑以上命令及任务 4 浏览器并发用例，预期通过；提交 `feat: preserve order and receipt transaction contracts`。

## Task 9: 每日工作、批量撤销及截图命令

**Files:** 新增 `web/src/application/{tasks,attachments,queries}.ts`、相应 `.test.ts`；扩展 `application/contracts.ts`。

**Interfaces:** `Services.tasks.setProgress(input: { taskId: string; gameDate: string; value: string | null; completed: boolean; backfilled: boolean }, token)`；`completeBatch(input: { operationId: string; scope: 'HOSTING_ORDER' | 'HOSTING_ALL' | 'SHORT_ORDER'; targetId: string; gameDate: string }, token): Promise<string>`；`undoBatch(batchId, token): Promise<{ restored: number; conflicts: number }>`。截图 `put(target: { taskId: string } | { occurrenceId: string }, file: Blob, token): Promise<string>`、`remove(id, token)`。查询 `Services.queries.view<T extends ViewName>(name: T, filter: ViewFilter[T]): Promise<ViewData[T]>`，ViewName 定为 today/owners/ownerDetail/orders/orderDetail/ledger/templates/settings，filter/result 与页面需求在此文件定义，均从一致快照派生。

- [ ] Step 1：写失败测试：重复打卡唯一、历史补录不抵今日、批量只改当前槽、再次独立编辑后撤销保留新值、NUMBER 精确进度、归档拒绝工作写入；运行 `npm test -- src/application/tasks.test.ts` 确认失败。
- [ ] Step 2：实现槽生成、批次审计前后值和冲突撤销，结合任务 6 日历；对未来日期、托管期外和作废订单按安卓规则拒绝。
- [ ] Step 3：写截图/查询失败测试：Blob 与元数据写入故障同回滚、替换失败旧图可读、移除后不导出孤儿、统计/详情跨表一致；实现附件和查询，所有跨表读取由 runtime 一致快照构造。
- [ ] Step 4：运行 `npm test -- src/application src/domain`，预期通过；再对新增资料执行任务 3 的跨端往返，预期规范化值一致；提交 `feat: add daily task batches and atomic attachments`。

## Task 10: 响应式界面、老板账号与模板完整页面

**Files:** 新增 `web/src/app/{App,Router,ServiceProvider}.tsx`、`web/src/app/theme.css`、`web/src/components/{AppShell,FormDialog,SaveFeedback}.tsx`、`web/src/features/accounts/{OwnersPage,OwnerDetailPage,AccountForm}.tsx`、`web/src/features/templates/{TemplatesPage,TemplateEditor}.tsx`、`web/tests/e2e/accountsTemplates.spec.ts`。

**Interfaces:** `ServiceProvider({ services, children })` 提供稳定的 `Services`；`useDatasetToken(): DatasetToken` 捕获表单代次；页面查询仅调用 Services。路由采用 hash 路由，支持静态主机；稳定地址包括 `#/today`、`#/owners/:id`、`#/orders/:id`、`#/ledger`、`#/templates`、`#/settings`。

- [ ] Step 1：写浏览器失败用例：创建老板/账号、遮挡与主动显示密码、归档默认收起及恢复、建立模板再导入另存、选择默认模板；375px 和 1440px 都可完成。运行 `npm run test:e2e -- tests/e2e/accountsTemplates.spec.ts` 确认功能未实现。
- [ ] Step 2：建立品牌主题、桌面侧栏/双栏与手机四入口，接入真实服务。实现可访问表单、44px 触控目标、主题与视图偏好保存在 localPreferences；原生/组件弹窗都管理焦点，取消不提交。
- [ ] Step 3：增加 200% 文字缩放、长中文、键盘 Tab/Escape、导入备注 `<script>` 仅作文本、未保存退出确认、复制失败、保存失败保留输入的断言；运行上述浏览器测试和 `npm run lint`，预期通过；提交 `feat: build responsive account and template pages`。

## Task 11: 今日、订单、日历、收款与统计页面

**Files:** 新增 `web/src/features/today/TodayPage.tsx`、`web/src/features/orders/{OrdersPage,OrderEditor,OrderDetailPage,HostingCalendar}.tsx`、`web/src/features/ledger/{LedgerPage,ReceiptForm,CorrectionDialog}.tsx`、`web/tests/e2e/workLedger.spec.ts`。

**Interfaces:** 使用任务 7–9 Services；新建表单创建一次 operationId 并在失败重试时复用，改载荷需新操作；route/filter 仅含无敏感信息的筛选与业务 ID。页面完成消息必须在事务 Promise 成功后显示。

- [ ] Step 1：写失败端到端流程：建托管单+首收→今日打卡/截图→续期→旧期收尾款→更正→核对实收/欠款；短期单完成/重开/纠错；月历补录/撤销；作废历史保留。运行 `npm run test:e2e -- tests/e2e/workLedger.spec.ts` 确认失败。
- [ ] Step 2：实现筛选列表、详情与管理确认、三段新建表单、批量操作及冲突反馈；接入日历与按期次收款。恢复后关闭旧详情和表单，不能沿用旧查询结果。
- [ ] Step 3：增加空/错误状态、未收清订单的归档行为、历史日与当前日提示、两标签页操作后刷新、前台跨游戏刷新时刻及重新聚焦后的今日事项更新；重跑上述测试及 `npm run typecheck`，预期通过；提交 `feat: complete daily work and ledger UI`。

## Task 12: 首次使用、备份恢复与存储状态页面

**Files:** 新增 `web/src/features/settings/{WelcomePage,SettingsPage,BackupPanel,RestorePreview}.tsx`、`web/src/platform/{files,storageEstimate}.ts`、`web/tests/e2e/backupSettings.spec.ts`。

**Interfaces:** `saveBackup(blob: Blob, suggestedName: string): Promise<'saved' | 'download-started' | 'cancelled'>`；`estimateStorage(): Promise<{ usage?: number; quota?: number; persisted: boolean | null }>`；页面使用任务 5 prepare/commit/cancel/export，密码仅组件会话生命周期存在。

- [ ] Step 1：写失败用例：空账本首次入口、选文件/密码取消、错误密码、预览数量/时间/金额、先导出当前资料或明确已另行备份再替换、拒绝过期确认；运行 `npm run test:e2e -- tests/e2e/backupSettings.spec.ts` 确认失败。
- [ ] Step 2：实现文件选择与支持时的显式保存，回退下载状态只能显示“已生成并发起下载”；`cancelled` 不更新成功记录。备份记录包含导出时 token，后续写入提示有修改；不暗示已确认外部文件存在。
- [ ] Step 3：接入持久存储请求/容量提示/不足错误；只读保留安卓三类提醒配置；说明更换浏览器/域名资料独立、清理站点资料会丢失本地内容、无本地加密保证。解密、预览与打包显示阶段进度而非伪造百分比，取消清理自己的候选/Worker。
- [ ] Step 4：测试输入大量附件导致配额不足仍保留原资料、备份后修改提示、恢复后身份重建、明密文真实下载可由安卓恢复；运行上述测试及任务 3 兼容测试预期通过；提交 `feat: deliver manual backup and restore workflow`。

## Task 13: 离线资源、应用更新与数据库升级协调

**Files:** 修改 `web/vite.config.ts`；新增 `web/src/platform/{pwa,updateGate}.ts`、`web/src/components/UpdateNotice.tsx`、`web/public/icons/`、`web/tests/browser/{offline,updates}.spec.ts`、`web/src/storage/migrations.test.ts`。

**Interfaces:** `UpdateGate.setBusy(reason: 'form' | 'write' | 'import' | 'export' | 'migration', active: boolean): void`；`activateUpdate(): Promise<'activated' | 'blocked'>`；跨标签页使用同一运行时锁，升级中拒绝新业务操作。忙碌注册由会话清理，异常卸载不永久锁死。

- [ ] Step 1：针对生产构建写失败测试：完整加载→断网重开→新建/打卡/收款/看图/导出；字体、图标和模板仍可用，网络无业务数据。运行 `npm run build`、`npm run test:e2e -- tests/browser/offline.spec.ts`，确认未缓存时失败。
- [ ] Step 2：配置仅应用壳与静态资源预缓存，复用品牌 SVG 生成图标，不缓存业务内容。离线首次从未访问的站点明确属于不可用场景，不伪造成功。
- [ ] Step 3：写更新/迁移失败测试：双标签页、未保存表单/导出中点击更新返回 blocked；更新按钮不强刷；模拟内部数据库 v1→v2 正常升级与中途失败，旧资料保留且 blocked 明示。运行 `npm run test:e2e -- tests/browser/updates.spec.ts`、`npm test -- src/storage/migrations.test.ts` 确认失败。
- [ ] Step 4：实现用户主动确认的更新门控和连接协调；迁移遇 blocked 请求其他页面关闭连接，失败提供重试或导出恢复入口，不能 deleteDatabase 清库兜底。预览阶段与 Worker 活跃也登记 busy。
- [ ] Step 5：重跑以上测试及 build，预期通过；提交 `feat: support offline use and coordinated updates`。

## Task 14: 跨浏览器、真实设备与最终交付验收

**Files:** 新增 `web/tests/e2e/acceptance.spec.ts`、`web/README.md`、`docs/handoffs/web-local-first.md`；更新根 `README.md` 与 `docs/testing.md` 的网页说明（保留安卓历史证据）。

**Interfaces:** 提供网页开发/构建/静态预览说明、备份兼容矩阵、稳定 HTTPS 来源要求、实测浏览器与容量表；不创建云端项目或公开发布。Playwright 配置 Chromium/Firefox/WebKit 三项目，真实 Safari/iPhone 不与 WebKit 自动化混称。

- [ ] Step 1：先写覆盖完整用户旅程的验收用例，断言已完成服务在新环境也能从安卓夹具导入→工作→导出→恢复；运行 `npm run test:e2e -- tests/e2e/acceptance.spec.ts`，如暴露失败按系统调试流程定位，禁止降低验收断言。
- [ ] Step 2：运行 `npm ci`、`npm run typecheck`、`npm run lint`、`npm test`、`npm run build`、`npm run test:e2e`；记录逐命令结果、失败/跳过和浏览器版本。仅重新运行被新改动影响的验证；最终全量对冻结提交执行一次。
- [ ] Step 3：运行 Android 夹具 JVM 与真实恢复设备测试，Android 既有回归至少 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；若修改共享测试工具，补跑相应设备回归。完成桌面真实 Safari、Android Chrome 和 iPhone Safari 手工文件导入/下载、锁、Blob、离线重启、软键盘与字体缩放验收；缺设备明确记未验证。
- [ ] Step 4：对桌面与移动真实环境分别测普通/加密小、中、大资料，逐级增加到格式或资源上限，记录实际字节、成功/失败、峰值内存可测值及失败后原账本完整性；不得从小夹具推断 512 MiB 全平台可用。真实进程退出恢复专门记录，不混入普通单页重载结果。
- [ ] Step 5：完成需求覆盖审阅，修复可复现缺陷并定向重验；交付记录给出截图、测试证据位置、边界与剩余事项。获得独立代码审阅并处理实质问题；执行模式与审阅方式按用户选择，未获选择前不派发代理。
- [ ] Step 6：运行 `git diff --check`，核对提交只含本计划范围文件；定向提交 `docs: record local-first web acceptance and usage`。按开发分支收尾流程给用户审阅，未经发布请求不部署或推送。

## 阶段交付与依赖

| 阶段 | 任务 | 可评审交付 | 进入下一阶段条件 |
|---|---|---|---|
| A 兼容基础 | 1–3 | 安卓与网页双向读写格式、真实恢复证据 | 18 表、提醒配置、加密和截图往返无损 |
| B 本地业务核心 | 4–9 | 恢复与并发保护、完整业务服务 | 失败回滚、陈旧写入拒绝、核心规则测试通过 |
| C 完整产品界面 | 10–12 | 可操作的本地网页版、手动迁移流程 | 两种布局日常闭环和失败反馈通过 |
| D 离线交付 | 13–14 | 可离线静态构建、版本更新及实测说明 | 兼容/业务/浏览器验收和审阅完成 |

默认按编号顺序执行，不把任务 10 的演示页面当作业务交付。云端备份、自动同步、公开站点发布和后台通知不属于本计划。

## 计划自审记录

- 设计 1–3 节由任务 7–12 覆盖，4–5 节由任务 1、4、6–9 覆盖，6–7 节由任务 1–5、12 覆盖，8–9 节由任务 4–5、10、12–14 覆盖；后续云端边界保持为非目标。
- 五项 Review Focus 分别有任务 2、5、4/5/8、12、13 的具名测试场景。
- 业务/备份/内部数据库版本分离；任务 5 的代次与任务 4 同一 token 定义；UI 只消费任务 7–9 Services，不直接引用 Dexie。
- 本文只编排未来工作，所有执行复选框保留未勾选，未宣称产品实现或测试通过。
