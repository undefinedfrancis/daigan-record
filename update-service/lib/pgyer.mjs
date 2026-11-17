/** Resolve a fresh CDN URL for one immutable Pgyer build. Never expose install credentials. */
export async function resolvePgyerDownload(buildKey, options = {}) {
 const apiKey=options.apiKey ?? process.env.PGYER_API_KEY;
 if(!apiKey || !/^[a-f0-9]{32}$/i.test(buildKey || '')) throw new Error('蒲公英配置不完整');
 const url=new URL('https://www.pgyer.com/apiv2/app/install');
 url.searchParams.set('_api_key',apiKey);
 url.searchParams.set('buildKey',buildKey);
 const password=options.password ?? process.env.PGYER_BUILD_PASSWORD;
 if(password) url.searchParams.set('buildPassword',password);
 let response;
 try {
 response=await (options.fetchImpl ?? fetch)(url,{redirect:'manual',signal:AbortSignal.timeout(8000),headers:{'user-agent':'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36'}});
 } catch {throw new Error('蒲公英下载连接暂不可用');}
 const location=response.headers.get('location');
 await response.body?.cancel();
 if(![301,302,303,307,308].includes(response.status) || !location) throw new Error('蒲公英未返回安装包下载地址');
 const target=new URL(location,url);
 if(target.protocol!=='https:' || target.username || target.password || target.searchParams.has('_api_key') || target.searchParams.has('buildPassword') || target.href.includes(apiKey)) throw new Error('蒲公英下载地址校验失败');
 return target.href;
}
