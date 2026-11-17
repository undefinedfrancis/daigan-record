/**
 * Immutable release store shared by the update service and the publishing tool.
 *
 * Layout:
 *   <store>/latest.json                                    activation pointer (only mutable file)
 *   <store>/releases/<releaseId>/release.json              metadata bound to one APK
 *   <store>/releases/<releaseId>/<artifactFile>            optional local copy of that APK
 *
 * A release record is written once. Its version, size, SHA-256 and signer details all
 * come from the same APK, so `/v1/check` can never advertise a hash that belongs to a
 * different build. Activation is a separate, atomic pointer switch that only happens
 * after the stored record and (when configured) the hosted artifact validate.
 */
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, open, readFile, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";

export const STORE_SCHEMA_VERSION = 1;
export const RELEASE_ID_PATTERN = /^[0-9]+-[0-9A-Za-z._-]+$/;
export const SHA256_PATTERN = /^[0-9a-f]{64}$/;
export const DEFAULT_PACKAGE_NAME = "com.daigan.record";

/** Release store root; override with RELEASE_STORE_DIR for staged/hosted layouts. */
export function configuredPackageName(override) {
  return override || process.env.APP_PACKAGE || DEFAULT_PACKAGE_NAME;
}

export function storeDir(override) {
  const dir = override || process.env.RELEASE_STORE_DIR || path.join(import.meta.dirname, "..", "releases");
  return path.resolve(dir);
}

export class ReleaseStoreError extends Error {
  constructor(message, code = "store_unavailable") {
    super(message);
    this.name = "ReleaseStoreError";
    this.code = code;
  }
}

export function releaseIdFor(versionCode, versionName) {
  return `${versionCode}-${versionName}`;
}

export function downloadUrlFor(record) {
  return `/v1/download/${record.releaseId}`;
}

export async function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(filePath);
    stream.on("error", reject);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

export async function readJsonFile(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

/** Write JSON through a synced temp file so readers never observe a partial pointer. */
export async function writeJsonAtomic(filePath, value) {
  await mkdir(path.dirname(filePath), { recursive: true });
  const temp = `${filePath}.tmp-${process.pid}-${Date.now()}`;
  await writeFile(temp, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  const handle = await open(temp, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temp, filePath);
}

/** Structural validation of a release record produced by the publishing tool. */
export function validateReleaseRecord(record, packageName = configuredPackageName()) {
  if (!record || typeof record !== "object") return ["发布记录不是对象"];
  const problems = [];
  if (record.schemaVersion !== STORE_SCHEMA_VERSION) problems.push("发布记录版本不受支持");
  if (typeof record.releaseId !== "string" || !RELEASE_ID_PATTERN.test(record.releaseId)) {
    problems.push("发布编号无效");
  }
  if (record.packageName !== packageName) problems.push("包名与更新服务不一致");
  if (!Number.isInteger(record.versionCode) || record.versionCode <= 0) problems.push("versionCode 无效");
  if (typeof record.versionName !== "string" || !record.versionName.trim()) problems.push("versionName 无效");
  if (!Number.isInteger(record.sizeBytes) || record.sizeBytes <= 0) problems.push("APK 大小无效");
  if (typeof record.sha256 !== "string" || !SHA256_PATTERN.test(record.sha256)) problems.push("SHA-256 无效");
  if (record.releaseId !== releaseIdFor(record.versionCode, record.versionName)) {
    problems.push("发布编号与版本号不一致");
  }
  if (record.immutable !== true) problems.push("发布记录未标记为不可变");
  if (record.artifactUrl != null) {
    let parsed = null;
    try {
      parsed = new URL(String(record.artifactUrl));
    } catch {
      problems.push("下载地址无效");
    }
    if (parsed && parsed.protocol !== "https:" && parsed.protocol !== "http:") problems.push("下载地址协议无效");
  }
  if (record.artifactFile != null) {
    const file = String(record.artifactFile);
    if (!file || file !== path.basename(file) || file.startsWith(".")) problems.push("本地产物文件名无效");
  }
  if (record.pgyerBuildKey != null && !/^[a-f0-9]{32}$/i.test(record.pgyerBuildKey)) problems.push("蒲公英构建编号无效");
  if (record.artifactFile == null && record.artifactUrl == null && !record.pgyerBuildKey) problems.push("发布记录缺少下载产物");
  const signer = record.signer;
  if (!signer || typeof signer !== "object") {
    problems.push("缺少签名信息");
  } else {
    if (typeof signer.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(signer.sha256)) problems.push("签名证书摘要无效");
    if (signer.verified !== true) problems.push("签名未通过校验");
    if (typeof signer.subject !== "string" || !signer.subject.trim()) problems.push("签名主体缺失");
  }
  return problems;
}

export async function readPointer(store) {
  try {
    return await readJsonFile(path.join(store, "latest.json"));
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

export async function readReleaseRecord(store, releaseId) {
  if (typeof releaseId !== "string" || !RELEASE_ID_PATTERN.test(releaseId)) return null;
  try {
    return await readJsonFile(path.join(store, "releases", releaseId, "release.json"));
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

/** Verify the local copy of a release still matches the bytes its record describes. */
async function verifyLocalArtifact(store, record) {
  const problems = [];
  const artifactPath = path.join(store, "releases", record.releaseId, record.artifactFile);
  const info = await stat(artifactPath).catch(() => null);
  if (!info?.isFile()) {
    problems.push("本地 APK 缺失");
  } else {
    if (info.size !== record.sizeBytes) problems.push(`APK 大小不符（期望 ${record.sizeBytes}，实际 ${info.size}）`);
    const digest = await sha256File(artifactPath);
    if (digest !== record.sha256) problems.push("APK 校验值不符");
  }
  return { artifactPath, problems };
}

/**
 * Verify the staged local copy matches its record, independently of whether the release
 * will be served remotely. Used by the publishing tool before activation so a corrupted
 * staged APK is caught even for remote releases.
 */
export async function verifyStagedArtifact(store, record) {
  if (!record.artifactFile) return { ok: false, artifactPath: null, problems: ["发布记录缺少本地产物"] };
  const { artifactPath, problems } = await verifyLocalArtifact(store, record);
  return { ok: problems.length === 0, artifactPath, problems };
}

/**
 * Resolve how the update service should serve a release's bytes.
 *
 * A record with a verified remote `artifactUrl` is always served by redirect, even when a
 * staged local copy also exists. Only explicit local/self-hosted releases (no
 * `artifactUrl`) stream their own bytes; remote integrity is enforced by the HTTPS origin
 * after the publishing tool verified the download location.
 */
export async function inspectArtifact(store, record) {
  if (record.pgyerBuildKey) return { ok: true, mode: "pgyer", artifactPath: null, problems: [] };
  if (record.artifactUrl) return { ok: true, mode: "remote", artifactPath: null, problems: [] };
  if (record.artifactFile) {
    const { artifactPath, problems } = await verifyLocalArtifact(store, record);
    return problems.length
      ? { ok: false, mode: "local", artifactPath: null, problems }
      : { ok: true, mode: "local", artifactPath, problems };
  }
  return { ok: false, mode: "none", artifactPath: null, problems: ["发布记录缺少下载产物"] };
}

/**
 * Resolve the release that clients should receive right now.
 *
 * The activated release wins only if its record and artifact validate. Otherwise the
 * previous release takes over, so a broken upload can never strand installed clients on
 * a hash that does not match the file.
 */
export async function resolveActiveRelease(options = {}) {
  const store = storeDir(options.store);
  const packageName = configuredPackageName(options.packageName);
  const pointer = await readPointer(store);
  if (!pointer?.releaseId) throw new ReleaseStoreError("尚未激活任何版本", "no_pointer");
  const chain = [pointer.releaseId, pointer.previousReleaseId].filter(Boolean);
  const rejected = [];
  for (const releaseId of chain) {
    const record = await readReleaseRecord(store, releaseId);
    if (!record) {
      rejected.push(`${releaseId}: 发布记录缺失`);
      continue;
    }
    const problems = validateReleaseRecord(record, packageName);
    if (problems.length) {
      rejected.push(`${releaseId}: ${problems.join("；")}`);
      continue;
    }
    const artifact = await inspectArtifact(store, record);
    if (!artifact.ok) {
      rejected.push(`${releaseId}: ${artifact.problems.join("；")}`);
      continue;
    }
    return { record, pointer, artifact, degraded: releaseId !== pointer.releaseId, rejected };
  }
  throw new ReleaseStoreError(`最新版本校验失败，且没有可回退的版本：${rejected.join(" | ")}`, "active_release_invalid");
}

/** The exact JSON contract installed 1.8.3 clients already parse. */
export function checkPayload(record) {
  return {
    packageName: record.packageName,
    versionCode: record.versionCode,
    versionName: record.versionName,
    notes: record.notes,
    sizeBytes: record.sizeBytes,
    sha256: record.sha256,
    downloadUrl: downloadUrlFor(record),
    releaseId: record.releaseId,
    signerSha256: record.signer.sha256,
  };
}
