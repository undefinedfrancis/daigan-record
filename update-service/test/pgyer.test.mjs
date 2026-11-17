import test from 'node:test';
import assert from 'node:assert/strict';
import {resolvePgyerDownload} from '../lib/pgyer.mjs';
test('pins build and keeps credentials away from public redirect',async()=>{
 let requested;
 const result=await resolvePgyerDownload('a'.repeat(32),{apiKey:'secret',fetchImpl:async(url,options)=>{requested=new URL(url); assert.equal(options.redirect,'manual'); return new Response(null,{status:302,headers:{location:'https://example.pgyer.com/file.apk?sign=abc'}});}});
 assert.equal(requested.searchParams.get('buildKey'),'a'.repeat(32));
 assert.equal(requested.searchParams.has('appKey'),false);
 assert.equal(result,'https://example.pgyer.com/file.apk?sign=abc');
});
test('rejects secret-bearing or insecure destinations and non-redirect errors',async()=>{
 for(const location of ['http://example.com/file.apk','https://example.com/?_api_key=secret'])
 await assert.rejects(resolvePgyerDownload('a'.repeat(32),{apiKey:'secret',fetchImpl:async()=>new Response(null,{status:302,headers:{location}})}));
 await assert.rejects(resolvePgyerDownload('a'.repeat(32),{apiKey:'secret',fetchImpl:async()=>new Response('{}',{status:200})}));
});
test('fixed Pgyer builds stream through update domain without exposing CDN',async(t)=>{
 const {mkdtemp,mkdir,writeFile,rm}=await import('node:fs/promises');
 const {tmpdir}=await import('node:os');
 const path=await import('node:path');
 const {createUpdateServer}=await import('../server.mjs');
 const store=await mkdtemp(path.join(tmpdir(),'pgyer-stream-'));
 t.after(()=>rm(store,{recursive:true,force:true}));
 const releaseId='13-1.8.4';
 await mkdir(path.join(store,'releases',releaseId),{recursive:true});
 await writeFile(path.join(store,'latest.json'),JSON.stringify({releaseId}));
 await writeFile(path.join(store,'releases',releaseId,'release.json'),JSON.stringify({schemaVersion:1,releaseId,packageName:'com.daigan.record',versionCode:13,versionName:'1.8.4',sizeBytes:5,sha256:'a'.repeat(64),pgyerBuildKey:'b'.repeat(32),immutable:true,signer:{verified:true,subject:'test',sha256:'c'.repeat(64)}}));
 const server=createUpdateServer({store,resolvePgyerDownload:async(key)=>{assert.equal(key,'b'.repeat(32));return 'https://cdn.example/apk';},fetch:async()=>new Response('bytes',{headers:{'content-length':'5'}})});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 t.after(()=>new Promise(r=>server.close(r)));
 const response=await fetch(`http://127.0.0.1:${server.address().port}/v1/download/${releaseId}`,{redirect:'manual'});
 assert.equal(response.status,200);assert.equal(response.headers.get('location'),null);assert.equal(await response.text(),'bytes');
});
test('Vercel native entrypoint exports an HTTP server',async()=>{
 const {default:entrypoint}=await import('../server.mjs');
 const {Server}=await import('node:http');
 assert.ok(entrypoint instanceof Server);
});
