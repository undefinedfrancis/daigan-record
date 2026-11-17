import { resolvePgyerDownload } from "../../lib/pgyer.mjs";
import { createReadStream } from "node:fs";

import {
  configuredPackageName,
  inspectArtifact,
  readReleaseRecord,
  resolveActiveRelease,
  storeDir,
  validateReleaseRecord,
} from "../../lib/release-store.mjs";

/**
 * `/v1/download` keeps serving the active release for clients that cached an old
 * download URL; `/v1/download/<releaseId>` is the immutable, version specific target
 * that `/v1/check` now advertises.
 *
 * Production artifacts are expected at an immutable HTTPS origin (`artifactUrl`), so the
 * function only redirects. Streaming is kept for self-hosted/local deployments; note that
 * a serverless platform caps response bodies well below APK size.
 */
export default async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    return res.status(405).json({ error: "method not allowed" });
  }
  const store = storeDir();
  const packageName = configuredPackageName();
  try {
    const requested = typeof req.query?.releaseId === "string" ? req.query.releaseId : null;
    let record;
    if (requested) {
      record = await readReleaseRecord(store, requested);
      if (!record) return res.status(404).json({ error: "该版本不存在" });
      const problems = validateReleaseRecord(record, packageName);
      if (problems.length) return res.status(409).json({ error: "该版本发布记录无效", detail: problems.join("；") });
    } else {
      record = (await resolveActiveRelease({ store, packageName })).record;
    }

    const artifact = await inspectArtifact(store, record);
    if (!artifact.ok) return res.status(503).json({ error: "更新包暂不可用", detail: artifact.problems.join("；") });

    res.setHeader("Cache-Control", "no-store");
    res.setHeader("x-release-id", record.releaseId);
    res.setHeader("x-artifact-sha256", record.sha256);
    if (artifact.mode === "remote" || artifact.mode === "pgyer") return res.redirect(302, artifact.mode === "pgyer" ? await resolvePgyerDownload(record.pgyerBuildKey) : record.artifactUrl);
    if (req.method === "HEAD") {
      res.setHeader("content-type", "application/vnd.android.package-archive");
      res.setHeader("content-length", String(record.sizeBytes));
      return res.status(200).end();
    }
    res.setHeader("content-type", "application/vnd.android.package-archive");
    res.setHeader("content-length", String(record.sizeBytes));
    return createReadStream(artifact.artifactPath).pipe(res);
  } catch (error) {
    return res.status(502).json({
      error: "更新包下载失败",
      detail: error instanceof Error ? error.message : "download failed",
    });
  }
}
