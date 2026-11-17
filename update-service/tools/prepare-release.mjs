// Runs in Vercel's build environment: credentials stay on Vercel.
import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { resolvePgyerDownload } from '../lib/pgyer.mjs';
import { fetchArtifactDigest } from './publish-release.mjs';
import { validateReleaseRecord, writeJsonAtomic } from '../lib/release-store.mjs';
const root=path.resolve(import.meta.dirname,'../releases');
const request=JSON.parse(await readFile(path.join(root,'pending.json'),'utf8'));
const record=JSON.parse(await readFile(path.join(root,'releases',request.releaseId,'release.json'),'utf8'));
if(validateReleaseRecord(record).length || !record.pgyerBuildKey) throw new Error('Invalid pending release');
const artifact=await fetchArtifactDigest(await resolvePgyerDownload(record.pgyerBuildKey));
if(artifact.sizeBytes!==record.sizeBytes || artifact.sha256!==record.sha256) throw new Error('Pgyer APK differs from accepted APK; deployment aborted');
await writeJsonAtomic(path.join(root,'latest.json'),{schemaVersion:1,releaseId:record.releaseId,previousReleaseId:request.previousReleaseId || null});
console.log(JSON.stringify({verified:true,releaseId:record.releaseId,...artifact}));
