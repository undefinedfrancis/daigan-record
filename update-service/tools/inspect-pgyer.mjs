const response=await fetch('https://www.pgyer.com/apiv2/app/view',{method:'POST',body:new URLSearchParams({_api_key:process.env.PGYER_API_KEY,appKey:process.env.PGYER_APP_KEY || 'ff1c7fcabdd2ed0631e4c58428cc99b2'}),signal:AbortSignal.timeout(15000)});
const {data,code}=await response.json();
if(code!==0 || !data) throw new Error('蒲公英版本查询失败');
console.log(JSON.stringify({buildKey:data.buildKey,packageName:data.buildIdentifier,versionCode:data.buildVersionNo,versionName:data.buildVersion,sizeBytes:data.buildFileSize}));
