import { resolvePgyerDownload } from "./lib/pgyer.mjs";
/**
 * Local update service.
 *
 * Serves exactly the routes installed 1.8.3 clients use:
 *   GET /v1/check                    -> release metadata (unchanged JSON contract)
 *   GET /v1/download                 -> currently active APK (legacy pointer)
 *   GET /v1/download/<releaseId>     -> immutable, version specific APK
 *
 * `/api/v1/...` is also accepted so the local server mirrors the deployed Vercel routes.
 */
import { createReadStream } from "node:fs";
import { createServer } from "node:http";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

import {
  ReleaseStoreError,
  checkPayload,
  configuredPackageName,
  inspectArtifact,
  readReleaseRecord,
  resolveActiveRelease,
  storeDir,
  validateReleaseRecord,
} from "./lib/release-store.mjs";

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

export function createUpdateServer(options = {}) {
  const store = storeDir(options.store);
  const packageName = configuredPackageName(options.packageName);

  // No caching: a pointer switch must be visible to the very next request, and every
  // read re-verifies the record against the stored APK instead of trusting memory.
  const active = () => resolveActiveRelease({ store, packageName });

  async function sendArtifact(req, res, record) {
    const artifact = await inspectArtifact(store, record);
    if (!artifact.ok) {
      json(res, 503, { error: "更新包暂不可用", detail: artifact.problems.join("；"), releaseId: record.releaseId });
      return;
    }
    if (artifact.mode === "pgyer") {
      const target = await (options.resolvePgyerDownload || resolvePgyerDownload)(record.pgyerBuildKey);
      const upstream = await (options.fetch || fetch)(target, {signal: AbortSignal.timeout(120000)});
      if (!upstream.ok || !upstream.body) throw new Error("蒲公英安装包暂不可用");
      const length = upstream.headers.get("content-length");
      if (length && Number(length) !== record.sizeBytes) { await upstream.body.cancel(); throw new Error("远程安装包大小不符"); }
      res.writeHead(200, {"content-type":"application/vnd.android.package-archive", "content-length":String(record.sizeBytes), "cache-control":"no-store", "x-release-id":record.releaseId, "x-artifact-sha256":record.sha256});
      if (req.method === "HEAD") { await upstream.body.cancel(); res.end(); return; }
      await pipeline(upstream.body, res);
      return;
    }
    if (artifact.mode === "remote") {
      const target = artifact.mode === "pgyer" ? await resolvePgyerDownload(record.pgyerBuildKey) : record.artifactUrl;
      res.writeHead(302, {
        location: target,
        "cache-control": "no-store",
        "x-release-id": record.releaseId,
        "x-artifact-sha256": record.sha256,
      });
      res.end();
      return;
    }
    res.writeHead(200, {
      "content-type": "application/vnd.android.package-archive",
      "content-length": String(record.sizeBytes),
      "cache-control": "no-store",
      "x-release-id": record.releaseId,
      "x-artifact-sha256": record.sha256,
    });
    if (req.method === "HEAD") {
      res.end();
      return;
    }
    await pipeline(createReadStream(artifact.artifactPath), res);
  }

  async function handle(req, res) {
    if (req.method !== "GET" && req.method !== "HEAD") return json(res, 405, { error: "不支持的请求方法" });
    const url = new URL(req.url || "/", "http://localhost");
    const pathname = url.pathname.replace(/^\/api(?=\/v1\/)/, "");

    if (pathname === "/api/build-info") {
      const {default: buildInfo} = await import("./api/build-info.mjs");
      res.status = function(code) { this.statusCode=code; return this; };
      res.json = function(body) { return json(this,this.statusCode || 200,body); };
      return buildInfo(req,res);
    }
    if (pathname === "/v1/check") {
      try {
        const { record, degraded } = await active();
        if (degraded) res.setHeader("x-release-degraded", "true");
        return json(res, 200, checkPayload(record));
      } catch (error) {
        const message = error instanceof Error ? error.message : "更新服务暂时不可用";
        return json(res, 502, { error: "更新服务暂时不可用", detail: message });
      }
    }

    if (pathname === "/v1/download") {
      try {
        const { record } = await active();
        return await sendArtifact(req, res, record);
      } catch (error) {
        return json(res, 503, { error: "更新包暂不可用", detail: error instanceof Error ? error.message : String(error) });
      }
    }

    const specific = pathname.match(/^\/v1\/download\/([^/]+)$/);
    if (specific) {
      const releaseId = decodeURIComponent(specific[1]);
      const record = await readReleaseRecord(store, releaseId);
      if (!record) return json(res, 404, { error: "该版本不存在" });
      const problems = validateReleaseRecord(record, packageName);
      if (problems.length) return json(res, 409, { error: "该版本发布记录无效", detail: problems.join("；") });
      return sendArtifact(req, res, record);
    }

    return json(res, 404, { error: "未找到接口" });
  }

  return createServer((req, res) => {
    handle(req, res).catch((error) => {
      if (!res.headersSent) json(res, 500, { error: error instanceof Error ? error.message : "内部错误" });
      else res.destroy(error instanceof Error ? error : undefined);
    });
  });
}

export { ReleaseStoreError };

// Vercel native Node entrypoint requires a server default export.
export default createUpdateServer();

const invokedDirectly = process.argv[1] && process.argv[1] === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  const port = Number(process.env.PORT || 8787);
  const store = storeDir();
  const server = createUpdateServer({ store });
  server.listen(port, "0.0.0.0", () => {
    console.log(`update service listening on ${port} (store: ${store})`);
  });
}
