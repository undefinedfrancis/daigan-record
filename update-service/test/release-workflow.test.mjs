import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { createUpdateServer } from "../server.mjs";
import { resolveActiveRelease } from "../lib/release-store.mjs";
import { publishRelease } from "../tools/publish-release.mjs";

const PACKAGE = "com.daigan.record";
const SIGNER = {
  verified: true,
  sha256: "b".repeat(64),
  subject: "C=US, O=Android, CN=Android Debug",
  schemes: ["v2"],
};

function inspector({ versionCode, versionName, signer = SIGNER, debuggable = false }) {
  return async () => ({ packageName: PACKAGE, versionCode, versionName, debuggable, signer });
}

async function workspace(t) {
  const dir = await mkdtemp(path.join(tmpdir(), "daigan-release-"));
  t.after(async () => rm(dir, { recursive: true, force: true }));
  return dir;
}

async function writeApk(dir, name, content) {
  const file = path.join(dir, name);
  await writeFile(file, content);
  return file;
}

/** Minimal immutable artifact origin, like the object storage that will host releases. */
async function artifactOrigin(t) {
  const files = new Map();
  const server = createHttpServer((req, res) => {
    const pathname = new URL(req.url, "http://localhost").pathname;
    const body = files.get(pathname);
    if (!body) {
      res.writeHead(404, { "content-type": "text/plain" });
      res.end("missing");
      return;
    }
    res.writeHead(200, {
      "content-type": "application/vnd.android.package-archive",
      "content-length": String(body.length),
    });
    res.end(req.method === "HEAD" ? undefined : body);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    url: (pathname) => `${base}${pathname}`,
    publish: (pathname, bytes) => files.set(pathname, bytes),
  };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function startUpdateServer(t, store) {
  const server = createUpdateServer({ store, packageName: PACKAGE });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}`;
}

test("release metadata is derived from one APK and staging alone never switches the pointer", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const payload = Buffer.from("immutable-apk-bytes-1.8.4");
  const apk = await writeApk(dir, "candidate.apk", payload);

  const result = await publishRelease({
    apkPath: apk,
    notes: "手机验收候选",
    store,
    stageOnly: true,
    inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
  });

  assert.equal(result.releaseId, "13-1.8.4");
  const record = JSON.parse(await readFile(path.join(store, "releases", "13-1.8.4", "release.json"), "utf8"));
  assert.equal(record.versionCode, 13);
  assert.equal(record.versionName, "1.8.4");
  assert.equal(record.packageName, PACKAGE);
  assert.equal(record.sizeBytes, payload.length);
  assert.equal(record.sha256, sha256(payload));
  assert.equal(record.signer.sha256, SIGNER.sha256);
  assert.equal(record.signer.verified, true);
  assert.equal(record.immutable, true);
  assert.equal(record.notes, "手机验收候选");

  const staged = await readFile(path.join(store, "releases", "13-1.8.4", "daigan-record-1.8.4.apk"));
  assert.deepEqual(staged, payload);

  await assert.rejects(stat(path.join(store, "latest.json")));
  await assert.rejects(resolveActiveRelease({ store }));
});

test("a hosted artifact mismatch aborts activation and keeps the previous release live", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const origin = await artifactOrigin(t);

  const previous = Buffer.from("apk-1.8.3-previous");
  const previousApk = await writeApk(dir, "1.8.3.apk", previous);
  origin.publish("/v1.8.3/daigan-record-1.8.3.apk", previous);
  await publishRelease({
    apkPath: previousApk,
    store,
    artifactUrl: origin.url("/v1.8.3/daigan-record-1.8.3.apk"),
    inspectApk: inspector({ versionCode: 12, versionName: "1.8.3" }),
  });

  const candidate = Buffer.from("apk-1.8.4-candidate");
  const candidateApk = await writeApk(dir, "1.8.4.apk", candidate);
  // The version specific URL still serves the old bytes, exactly the regression the
  // independent RELEASE_SHA256 arrangement allowed to reach clients.
  origin.publish("/v1.8.4/daigan-record-1.8.4.apk", Buffer.from("stale-upload"));

  await assert.rejects(
    publishRelease({
      apkPath: candidateApk,
      store,
      artifactUrl: origin.url("/v1.8.4/daigan-record-1.8.4.apk"),
      inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
    }),
    /线上 APK 与本地 APK 不一致/,
  );

  const pointer = JSON.parse(await readFile(path.join(store, "latest.json"), "utf8"));
  assert.equal(pointer.releaseId, "12-1.8.3");
  const active = await resolveActiveRelease({ store });
  assert.equal(active.record.releaseId, "12-1.8.3");
  assert.equal(active.degraded, false);
  // The rejected release is only staged, never activated.
  const staged = JSON.parse(await readFile(path.join(store, "releases", "13-1.8.4", "release.json"), "utf8"));
  assert.equal(staged.sha256, sha256(candidate));
});

test("activation switches the pointer only after the hosted bytes match", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const origin = await artifactOrigin(t);

  for (const release of [
    { versionCode: 12, versionName: "1.8.3", bytes: Buffer.from("apk-v12") },
    { versionCode: 13, versionName: "1.8.4", bytes: Buffer.from("apk-v13") },
  ]) {
    const apk = await writeApk(dir, `${release.versionName}.apk`, release.bytes);
    const pathname = `/v1.${release.versionName}/daigan-record-${release.versionName}.apk`;
    origin.publish(pathname, release.bytes);
    await publishRelease({
      apkPath: apk,
      store,
      artifactUrl: origin.url(pathname),
      inspectApk: inspector(release),
    });
  }

  const pointer = JSON.parse(await readFile(path.join(store, "latest.json"), "utf8"));
  assert.equal(pointer.releaseId, "13-1.8.4");
  assert.equal(pointer.previousReleaseId, "12-1.8.3");
  assert.equal(pointer.sha256, sha256(Buffer.from("apk-v13")));

  const active = await resolveActiveRelease({ store });
  assert.equal(active.record.releaseId, "13-1.8.4");
  assert.equal(active.degraded, false);
});

test("published releases are immutable and version numbers cannot silently regress", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const origin = await artifactOrigin(t);
  const bytes = Buffer.from("apk-v13");
  const apk = await writeApk(dir, "1.8.4.apk", bytes);
  origin.publish("/release-13.apk", bytes);
  await publishRelease({
    apkPath: apk,
    store,
    artifactUrl: origin.url("/release-13.apk"),
    inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
  });

  const otherBytes = Buffer.from("apk-v13-rebuilt");
  const rebuilt = await writeApk(dir, "rebuilt.apk", otherBytes);
  origin.publish("/release-13-rebuilt.apk", otherBytes);
  await assert.rejects(
    publishRelease({
      apkPath: rebuilt,
      store,
      artifactUrl: origin.url("/release-13-rebuilt.apk"),
      inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
      allowSameVersion: true,
    }),
    /不可变版本 13-1.8.4 已(存在且 APK 字节不同|激活但产物校验值不同)/,
  );

  const olderBytes = Buffer.from("apk-v11");
  const older = await writeApk(dir, "1.8.1.apk", olderBytes);
  origin.publish("/release-11.apk", olderBytes);
  await assert.rejects(
    publishRelease({
      apkPath: older,
      store,
      artifactUrl: origin.url("/release-11.apk"),
      inspectApk: inspector({ versionCode: 11, versionName: "1.8.1" }),
    }),
    /低于当前 13/,
  );

  const pointer = JSON.parse(await readFile(path.join(store, "latest.json"), "utf8"));
  assert.equal(pointer.releaseId, "13-1.8.4");
});

test("activation without a verified hosted artifact is refused", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const bytes = Buffer.from("apk-v13");
  const apk = await writeApk(dir, "1.8.4.apk", bytes);

  await assert.rejects(
    publishRelease({
      apkPath: apk,
      store,
      inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
    }),
    /--stage-only/,
  );
  await assert.rejects(stat(path.join(store, "latest.json")));
  // The artifact stayed staged for a later, verified activation.
  const record = JSON.parse(await readFile(path.join(store, "releases", "13-1.8.4", "release.json"), "utf8"));
  assert.equal(record.sha256, sha256(bytes));
});

test("/v1/check keeps the 1.8.3 contract and download targets are version specific", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");
  const bytes = Buffer.from("apk-bytes-for-http");
  const apk = await writeApk(dir, "1.8.4.apk", bytes);
  await publishRelease({
    apkPath: apk,
    store,
    notes: "计数托管与颜色修复",
    stageOnly: true,
    inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
  });
  // Activate locally (self-hosted store) so the endpoints can be exercised end to end.
  await publishRelease({
    apkPath: apk,
    store,
    notes: "计数托管与颜色修复",
    allowLocalArtifact: true,
    inspectApk: inspector({ versionCode: 13, versionName: "1.8.4" }),
  });

  const base = await startUpdateServer(t, store);
  const response = await fetch(`${base}/v1/check`);
  assert.equal(response.status, 200);
  const payload = await response.json();
  for (const key of ["packageName", "versionCode", "versionName", "notes", "sizeBytes", "sha256", "downloadUrl"]) {
    assert.ok(Object.hasOwn(payload, key), `missing ${key}`);
  }
  assert.equal(payload.packageName, PACKAGE);
  assert.equal(payload.versionCode, 13);
  assert.equal(payload.versionName, "1.8.4");
  assert.equal(payload.notes, "计数托管与颜色修复");
  assert.equal(payload.sizeBytes, bytes.length);
  assert.equal(payload.sha256, sha256(bytes));
  assert.equal(payload.downloadUrl, "/v1/download/13-1.8.4");

  const download = await fetch(`${base}${payload.downloadUrl}`);
  assert.equal(download.status, 200);
  assert.equal(download.headers.get("x-release-id"), "13-1.8.4");
  assert.equal(download.headers.get("x-artifact-sha256"), sha256(bytes));
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);

  const legacy = await fetch(`${base}/v1/download`);
  assert.deepEqual(Buffer.from(await legacy.arrayBuffer()), bytes);

  const apiPrefixed = await fetch(`${base}/api/v1/download/13-1.8.4`);
  assert.deepEqual(Buffer.from(await apiPrefixed.arrayBuffer()), bytes);

  const head = await fetch(`${base}${payload.downloadUrl}`, { method: "HEAD" });
  assert.equal(head.headers.get("content-length"), String(bytes.length));

  const missing = await fetch(`${base}/v1/download/99-9.9.9`);
  assert.equal(missing.status, 404);
});

test("a corrupted active APK degrades to the previous release instead of advertising it", async (t) => {
  const dir = await workspace(t);
  const store = path.join(dir, "store");

  for (const release of [
    { versionCode: 12, versionName: "1.8.3", bytes: Buffer.from("apk-v12-good") },
    { versionCode: 13, versionName: "1.8.4", bytes: Buffer.from("apk-v13-good") },
  ]) {
    const apk = await writeApk(dir, `${release.versionName}.apk`, release.bytes);
    await publishRelease({
      apkPath: apk,
      store,
      allowLocalArtifact: true,
      inspectApk: inspector(release),
    });
  }

  const activePath = path.join(store, "releases", "13-1.8.4", "daigan-record-1.8.4.apk");
  await writeFile(activePath, Buffer.from("truncated"));

  const active = await resolveActiveRelease({ store });
  assert.equal(active.record.releaseId, "12-1.8.3");
  assert.equal(active.degraded, true);

  const base = await startUpdateServer(t, store);
  const response = await fetch(`${base}/v1/check`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("x-release-degraded"), "true");
  assert.equal((await response.json()).downloadUrl, "/v1/download/12-1.8.3");

  // The broken release is still identifiable and refuses to serve bytes.
  const broken = await fetch(`${base}/v1/download/13-1.8.4`);
  assert.equal(broken.status, 503);
});
