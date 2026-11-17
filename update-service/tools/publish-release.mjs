import { resolvePgyerDownload } from "../lib/pgyer.mjs";
/**
 * Stage and activate an immutable update release.
 *
 * Everything published is derived from one APK: package name, versionCode, versionName,
 * size, SHA-256 and the signing certificate. The download target is version specific
 * (`/v1/download/<releaseId>`), so a later upload can never change the bytes behind an
 * already published version number.
 *
 * Activation order:
 *   1. stage `releases/<releaseId>/{release.json,apk}`
 *   2. re-read the staged copy and compare size + SHA-256
 *   3. download the hosted artifact and compare size + SHA-256 again
 *   4. atomically switch `latest.json`
 *
 * If any step fails, `latest.json` is untouched and the previously activated release
 * keeps serving. There is no code path that publishes a hash independently of an APK.
 */
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import {
  STORE_SCHEMA_VERSION,
  configuredPackageName,
  downloadUrlFor,
  readPointer,
  readReleaseRecord,
  releaseIdFor,
  sha256File,
  storeDir,
  validateReleaseRecord,
  verifyStagedArtifact,
  writeJsonAtomic,
} from "../lib/release-store.mjs";

const execFileAsync = promisify(execFile);

function sdkRoot() {
  return process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || null;
}

async function resolveBuildTool(name, explicit) {
  if (explicit) return explicit;
  const root = sdkRoot();
  if (!root) throw new Error(`未找到 Android SDK，请设置 ANDROID_HOME 或显式传入 ${name} 路径`);
  const buildToolsDir = path.join(root, "build-tools");
  const versions = await readdir(buildToolsDir).catch(() => []);
  const ordered = versions.sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  for (const version of ordered) {
    const candidate = path.join(buildToolsDir, version, name);
    if (existsSync(candidate)) return candidate;
  }
  throw new Error(`Android build-tools 中缺少 ${name}`);
}

/**
 * Read package/version/signer facts straight from the APK through the Android SDK.
 * No value here is hand-maintained.
 */
export async function inspectApkWithSdk(apkPath, options = {}) {
  const aapt = await resolveBuildTool(process.platform === "win32" ? "aapt2.exe" : "aapt2", options.aapt);
  const apksigner = await resolveBuildTool(process.platform === "win32" ? "apksigner.bat" : "apksigner", options.apksigner);

  const { stdout: badging } = await execFileAsync(aapt, ["dump", "badging", apkPath], { maxBuffer: 8 * 1024 * 1024 });
  const packageLine = badging.split(/\r?\n/).find((line) => line.startsWith("package: ")) || "";
  const value = (key) => {
    const match = packageLine.match(new RegExp(`${key}='([^']*)'`));
    return match ? match[1] : "";
  };
  const packageName = value("name");
  const versionCode = Number(value("versionCode"));
  const versionName = value("versionName");
  if (!packageName || !Number.isInteger(versionCode) || versionCode <= 0 || !versionName) {
    throw new Error("无法从 APK 读取包名或版本信息");
  }

  let signerOutput;
  try {
    ({ stdout: signerOutput } = await execFileAsync(apksigner, ["verify", "--print-certs", "--verbose", apkPath], {
      maxBuffer: 8 * 1024 * 1024,
    }));
  } catch (error) {
    const details = String(error.stderr || error.stdout || error.message || "").trim();
    throw new Error(`APK 签名校验失败${details ? `：${details.split(/\r?\n/)[0]}` : ""}`);
  }
  const signerSha256 = (signerOutput.match(/Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)/) || [])[1] || "";
  const signerSubject = (signerOutput.match(/Signer #1 certificate DN:\s*(.+)/) || [])[1]?.trim() || "";
  if (!/^[0-9a-fA-F]{64}$/.test(signerSha256) || !signerSubject) throw new Error("无法从 APK 读取签名证书信息");
  const schemes = [];
  for (const [label, key] of [
    ["v1", "v1 scheme"],
    ["v2", "v2 scheme"],
    ["v3", "v3 scheme"],
    ["v3.1", "v3.1 scheme"],
  ]) {
    const match = signerOutput.match(new RegExp(`Verified using ${key.replace(".", "\\.")}[^:]*: (true|false)`));
    if (match && match[1] === "true") schemes.push(label);
  }

  return {
    packageName,
    versionCode,
    versionName,
    debuggable: /^application-debuggable:/m.test(badging),
    signer: {
      verified: true,
      sha256: signerSha256.toLowerCase(),
      subject: signerSubject,
      schemes,
    },
  };
}

/** Stream a hosted artifact and hash it, so a downloaded file is compared to the APK. */
export async function fetchArtifactDigest(url, options = {}) {
  const response = await fetch(url, {
    redirect: "follow",
    headers: { accept: "application/vnd.android.package-archive,*/*" },
    signal: AbortSignal.timeout(options.timeoutMs ?? 60_000),
  });
  if (!response.ok || !response.body) throw new Error(`线上 APK 下载校验失败（HTTP ${response.status}）`);
  const hash = createHash("sha256");
  let sizeBytes = 0;
  for await (const chunk of response.body) {
    sizeBytes += chunk.byteLength;
    hash.update(chunk);
  }
  if (sizeBytes <= 0) throw new Error("线上 APK 内容为空");
  return { sizeBytes, sha256: hash.digest("hex") };
}

function sanitizeFileName(versionName) {
  const cleaned = versionName.replace(/[^0-9A-Za-z._-]/g, "-");
  return `daigan-record-${cleaned}.apk`;
}

/**
 * Stage (and optionally activate) one release.
 *
 * @param {object} options
 * @param {string} options.apkPath
 * @param {string} [options.notes]
 * @param {string} [options.store]
 * @param {string} [options.artifactUrl] immutable, version specific download URL
 * @param {boolean} [options.stageOnly]
 * @param {boolean} [options.allowLocalArtifact] activate against the verified staged copy
 * @param {boolean} [options.allowDowngrade]
 * @param {boolean} [options.allowSameVersion]
 * @param {string} [options.expectedSignerSha256]
 * @param {boolean} [options.dryRun]
 * @param {Function} [options.inspectApk] injectable inspector (tests)
 */
export async function publishRelease(options) {
  const {
    apkPath,
    notes = "",
    artifactUrl = null,
    stageOnly = false,
    allowLocalArtifact = false,
    allowDowngrade = false,
    allowSameVersion = false,
    expectedSignerSha256 = null,
    dryRun = false,
    inspectApk = inspectApkWithSdk,
  } = options;
  if (!apkPath) throw new Error("必须提供 APK 路径");
  const packageName = configuredPackageName(options.packageName);
  const store = storeDir(options.store);

  const apkInfo = await stat(apkPath);
  if (!apkInfo.isFile() || apkInfo.size <= 0) throw new Error(`APK 不存在或为空：${apkPath}`);
  const inspected = await inspectApk(apkPath);
  if (inspected.packageName !== packageName) {
    throw new Error(`APK 包名为 ${inspected.packageName}，与更新服务的 ${packageName} 不一致`);
  }
  if (expectedSignerSha256 && inspected.signer.sha256 !== expectedSignerSha256.toLowerCase()) {
    throw new Error(`签名证书不匹配：期望 ${expectedSignerSha256}，实际 ${inspected.signer.sha256}`);
  }

  const sizeBytes = apkInfo.size;
  const sha256 = await sha256File(apkPath);
  const releaseId = releaseIdFor(inspected.versionCode, inspected.versionName);
  const releaseDir = path.join(store, "releases", releaseId);
  const artifactFile = sanitizeFileName(inspected.versionName);
  const recordPath = path.join(releaseDir, "release.json");

  const record = {
    schemaVersion: STORE_SCHEMA_VERSION,
    releaseId,
    packageName,
    versionCode: inspected.versionCode,
    versionName: inspected.versionName,
    notes,
    sizeBytes,
    sha256,
    artifactFile,
    artifactUrl: artifactUrl || null,
    ...(options.pgyerBuildKey ? {pgyerBuildKey: options.pgyerBuildKey} : {}),
    signer: {
      verified: inspected.signer.verified === true,
      sha256: inspected.signer.sha256,
      subject: inspected.signer.subject,
      schemes: inspected.signer.schemes || [],
    },
    debuggable: inspected.debuggable === true,
    immutable: true,
    createdAt: new Date().toISOString(),
  };
  const recordProblems = validateReleaseRecord(record, packageName);
  if (recordProblems.length) throw new Error(`发布记录校验失败：${recordProblems.join("；")}`);

  const pointer = await readPointer(store);
  let activeRecord = pointer?.releaseId ? await readReleaseRecord(store, pointer.releaseId) : null;
  const activeReleaseId = activeRecord?.releaseId ?? pointer?.releaseId ?? null;
  const activeVersionCode = activeRecord?.versionCode ?? (Number.isInteger(pointer?.versionCode) ? pointer.versionCode : 0);

  if (activeReleaseId === releaseId) {
    if (activeRecord && activeRecord.sha256 !== sha256) {
      throw new Error(`不可变版本 ${releaseId} 已激活但产物校验值不同，拒绝覆盖`);
    }
    if (!allowSameVersion) {
      // Same bytes on the same version is an idempotent republish; keep serving it.
      return { record, store, recordPath, releaseId, staged: false, activated: false, unchanged: true, previousReleaseId: pointer?.previousReleaseId ?? null };
    }
  }
  if (activeVersionCode && record.versionCode < activeVersionCode && !allowDowngrade) {
    throw new Error(`新版本 versionCode ${record.versionCode} 低于当前 ${activeVersionCode}，如确需回退请加 --allow-downgrade`);
  }
  if (activeVersionCode && record.versionCode === activeVersionCode && activeReleaseId !== releaseId && !allowSameVersion) {
    throw new Error(`versionCode ${record.versionCode} 已被 ${activeReleaseId} 使用，如确需替换请加 --allow-same-version`);
  }

  const existing = await readReleaseRecord(store, releaseId);
  if (existing && (existing.sha256 !== sha256 || existing.sizeBytes !== sizeBytes)) {
    throw new Error(`不可变版本 ${releaseId} 已存在且 APK 字节不同，请提升 versionCode 后重新发布`);
  }

  if (dryRun) {
    return { record, store, recordPath, releaseId, staged: false, activated: false, dryRun: true, previousReleaseId: activeReleaseId };
  }

  // A finalized (already activated) release is immutable: its download contract never
  // changes, and republishing the exact same bytes is an idempotent no-op.
  if (existing && existing.activatedAt) {
    return {
      record: existing,
      store,
      recordPath,
      releaseId,
      staged: false,
      activated: false,
      unchanged: true,
      previousReleaseId: pointer?.previousReleaseId ?? null,
    };
  }

  await mkdir(releaseDir, { recursive: true });
  const stagedPath = path.join(releaseDir, artifactFile);
  if (!existing) {
    await copyFile(apkPath, stagedPath);
  }
  // Write even over an unfinalized staged record so activation persists the verified
  // remote download location; the finalized branch above is never overwritten.
  await writeJsonAtomic(recordPath, record);

  const stagedCheck = await verifyStagedArtifact(store, record);
  if (!stagedCheck.ok) throw new Error(`暂存 APK 校验失败：${stagedCheck.problems.join("；")}`);

  if (artifactUrl || options.pgyerBuildKey) {
    const hosted = await fetchArtifactDigest(options.pgyerBuildKey ? await resolvePgyerDownload(options.pgyerBuildKey) : artifactUrl);
    if (hosted.sizeBytes !== sizeBytes || hosted.sha256 !== sha256) {
      throw new Error(
        `线上 APK 与本地 APK 不一致（线上 ${hosted.sizeBytes}B/${hosted.sha256.slice(0, 12)}…，本地 ${sizeBytes}B/${sha256.slice(0, 12)}…），未切换最新版本`,
      );
    }
  }

  if (stageOnly) {
    return { record, store, recordPath, releaseId, staged: true, activated: false, previousReleaseId: activeReleaseId, hostedVerified: Boolean(artifactUrl) };
  }
  if (!artifactUrl && !options.pgyerBuildKey && !allowLocalArtifact) {
    throw new Error("激活前需要 --artifact-url 并完成线上校验；只暂存请使用 --stage-only");
  }

  const finalized = { ...record, activatedAt: new Date().toISOString() };
  await writeJsonAtomic(recordPath, finalized);

  const pointerPath = path.join(store, "latest.json");
  await writeJsonAtomic(pointerPath, {
    schemaVersion: STORE_SCHEMA_VERSION,
    releaseId,
    versionCode: finalized.versionCode,
    versionName: finalized.versionName,
    sizeBytes,
    sha256,
    downloadUrl: downloadUrlFor(finalized),
    activatedAt: finalized.activatedAt,
    previousReleaseId: activeReleaseId && activeReleaseId !== releaseId ? activeReleaseId : pointer?.previousReleaseId ?? null,
  });

  return { record: finalized, store, recordPath, pointerPath, releaseId, staged: true, activated: true, previousReleaseId: activeReleaseId, hostedVerified: Boolean(artifactUrl) };
}

function parseArgs(argv) {
  const flags = new Map();
  const valueFlags = new Set(["pgyer-build-key", "apk", "notes", "notes-file", "store", "artifact-url", "expected-signer-sha256", "aapt", "apksigner"]);
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith("--")) throw new Error(`无法识别的参数：${arg}`);
    const [name, inline] = arg.slice(2).split("=", 2);
    if (valueFlags.has(name)) {
      const next = inline ?? argv[index + 1];
      if (next === undefined || (inline === undefined && next.startsWith("--"))) throw new Error(`参数 --${name} 缺少取值`);
      flags.set(name, next);
      if (inline === undefined) index += 1;
    } else {
      const value = inline === undefined ? true : inline !== "false";
      flags.set(name, value);
    }
  }
  return flags;
}

async function main() {
  const flags = parseArgs(process.argv.slice(2));
  if (flags.get("help")) {
    console.log(`用法：node tools/publish-release.mjs --apk <apk> [选项]

必填：
  --apk <path>                       要发布的 APK

选项：
  --notes <text> | --notes-file <p>  /v1/check 返回的更新说明
  --store <dir>                      发布库目录（默认 RELEASE_STORE_DIR 或 update-service/releases）
  --artifact-url <url>               该版本的固定下载地址（不可变，建议含版本号）
  --stage-only                       只写入 release.json 与 APK，不切换 latest.json
  --allow-local-artifact             允许用校验过的本地暂存 APK 激活（自托管）
  --allow-downgrade                 允许发布更低 versionCode
  --allow-same-version               允许同 versionCode 不同字节（默认拒绝）
  --expected-signer-sha256 <hex>     要求签名证书摘要匹配，防止换签
  --dry-run                          只打印将写入的记录
  --aapt <path> / --apksigner <path> 覆盖 Android SDK 工具路径

激活时默认要求 --artifact-url，并先回下载比对大小与 SHA-256；校验失败不会切换 latest.json。`);
    return;
  }
  const apkPath = flags.get("apk");
  if (!apkPath) throw new Error("缺少 --apk");
  let notes = flags.get("notes") || "";
  if (flags.get("notes-file")) notes = (await readFile(flags.get("notes-file"), "utf8")).trim();

  const result = await publishRelease({
    apkPath: path.resolve(apkPath),
    notes,
    store: flags.get("store"),
    artifactUrl: flags.get("artifact-url") || null,
    pgyerBuildKey: flags.get("pgyer-build-key") || null,
    stageOnly: flags.get("stage-only") === true,
    allowLocalArtifact: flags.get("allow-local-artifact") === true,
    allowDowngrade: flags.get("allow-downgrade") === true,
    allowSameVersion: flags.get("allow-same-version") === true,
    expectedSignerSha256: flags.get("expected-signer-sha256") || null,
    dryRun: flags.get("dry-run") === true,
    inspectApk: (file) => inspectApkWithSdk(file, { aapt: flags.get("aapt"), apksigner: flags.get("apksigner") }),
  });

  const summary = {
    releaseId: result.releaseId,
    versionCode: result.record.versionCode,
    versionName: result.record.versionName,
    packageName: result.record.packageName,
    sizeBytes: result.record.sizeBytes,
    sha256: result.record.sha256,
    signerSha256: result.record.signer.sha256,
    signerSubject: result.record.signer.subject,
    debuggable: result.record.debuggable,
    artifactUrl: result.record.artifactUrl,
    downloadUrl: downloadUrlFor(result.record),
    store: result.store,
    recordPath: result.recordPath,
    staged: result.staged,
    activated: result.activated,
    unchanged: result.unchanged === true,
    dryRun: result.dryRun === true,
    previousReleaseId: result.previousReleaseId ?? null,
  };
  console.log(JSON.stringify(summary, null, 2));
  if (!summary.staged && !summary.unchanged && !summary.dryRun) console.log("未写入任何内容。");
  if (summary.staged && !summary.activated) console.log("已暂存，但最新版本指针未切换（未激活）。");
  if (summary.activated) console.log("已切换最新版本指针；旧版本记录保留用于回退。");
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  main().catch((error) => {
    console.error(`发布失败：${error instanceof Error ? error.message : error}`);
    console.error("上一版本保持激活状态。");
    process.exitCode = 1;
  });
}
