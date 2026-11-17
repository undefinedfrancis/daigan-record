import { checkPayload, resolveActiveRelease } from "../../lib/release-store.mjs";

/**
 * Response shape is unchanged from the contract installed 1.8.3 clients parse.
 * Values now come from one immutable release record instead of live upstream metadata
 * plus an independently maintained hash.
 */
export default async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "HEAD") {
    return res.status(405).json({ error: "method not allowed" });
  }
  try {
    const { record, degraded } = await resolveActiveRelease();
    res.setHeader("Cache-Control", "no-store");
    if (degraded) res.setHeader("x-release-degraded", "true");
    return res.status(200).json(checkPayload(record));
  } catch (error) {
    return res.status(502).json({
      error: "更新服务暂时不可用",
      detail: error instanceof Error ? error.message : "update check failed",
    });
  }
}
