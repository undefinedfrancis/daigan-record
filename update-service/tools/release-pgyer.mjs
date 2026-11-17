#!/usr/bin/env node
// After uploading this APK to Pgyer, run: npm run release:pgyer -- /absolute/app.apk
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile} from 'node:fs/promises';
import {publishRelease,inspectApkWithSdk,fetchArtifactDigest} from './publish-release.mjs';
import {writeJsonAtomic} from '../lib/release-store.mjs';
const exec=promisify(execFile);
const root=path.resolve(import.meta.dirname,'..');
const base='https://update.undefinedyou.com';
const signer='a0f5dfb7d389ddddba6d81c0f34e3b66dada4fd1cdcc9a36673fe9cf45ed5cb2';
const savedCli=await readFile(path.join(root,'.vercel/cli-path'),'utf8').catch(()=> '');
const cli=process.env.VERCEL_CLI || savedCli.trim() || 'vercel';
async function vercel(args){
 const {stdout,stderr}=await exec(cli,args,{cwd:root,maxBuffer:4*1024*1024,timeout:240000});
 // CLI output includes only deployment URLs; credentials remain server-side.
 process.stderr.write(stderr);
 return stdout;
}
async function json(url){const res=await fetch(url,{signal:AbortSignal.timeout(20000)});if(!res.ok)throw new Error('Update service unavailable');return res.json();}
async function main(){
 if(!process.argv[2])throw new Error('Usage: npm run release:pgyer -- /absolute/app.apk');
 const apk=path.resolve(process.argv[2]);
 const inspected=await inspectApkWithSdk(apk);
 const current=await json(base+'/v1/check');
 const build=await json(base+'/api/build-info');
 if(inspected.versionCode<=current.versionCode)throw new Error('New versionCode must exceed the live release');
 if(build.versionCode!==inspected.versionCode || build.versionName!==inspected.versionName || build.packageName!==inspected.packageName)throw new Error('Upload this exact APK to Pgyer first');
 const staged=await publishRelease({apkPath:apk,stageOnly:true,expectedSignerSha256:signer});
 if(staged.record.sizeBytes!==build.sizeBytes)throw new Error('Pgyer size differs from local APK');
 await writeJsonAtomic(staged.recordPath,{...staged.record,pgyerBuildKey:build.buildKey});
 const store=path.join(root,'releases');
 await writeJsonAtomic(path.join(store,'pending.json'),{releaseId:staged.releaseId,previousReleaseId:current.releaseId || null});
 const old=JSON.parse(await readFile(path.join(root,'.vercel/last-success.json'),'utf8'));
 const output=await vercel(['deploy','--prod','--skip-domain','--yes']);
 const match=output.match(/https:\/\/[a-z0-9-]+\.vercel\.app/);
 if(!match)throw new Error('Deployment URL missing; production unchanged');
 const deployment=match[0];
 // Vercel build downloads the pinned build and verifies SHA before this point.
 await vercel(['alias','set',deployment,'update.undefinedyou.com','--scope','jingle3083176110-1516']);
 try{
 const live=await json(base+'/v1/check');
 if(live.releaseId!==staged.releaseId || live.sha256!==staged.record.sha256)throw new Error('Live metadata mismatch');
 const digest=await fetchArtifactDigest(new URL(live.downloadUrl,base));
 if(digest.sizeBytes!==live.sizeBytes || digest.sha256!==live.sha256)throw new Error('Live APK mismatch');
 await writeJsonAtomic(path.join(root,'.vercel/last-success.json'),{deployment,releaseId:live.releaseId,sha256:live.sha256});
 console.log(JSON.stringify({published:true,releaseId:live.releaseId,...digest}));
 }catch(error){await vercel(['alias','set',old.deployment,'update.undefinedyou.com','--scope','jingle3083176110-1516']);throw error;}
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
