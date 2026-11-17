export default async function handler(req,res){
 try{
 const response=await fetch('https://www.pgyer.com/apiv2/app/view',{method:'POST',body:new URLSearchParams({_api_key:process.env.PGYER_API_KEY,appKey:process.env.PGYER_APP_KEY || 'ff1c7fcabdd2ed0631e4c58428cc99b2'}),signal:AbortSignal.timeout(8000)});
 const {data,code}=await response.json();
 if(code!==0 || data?.buildIdentifier!==(process.env.APP_PACKAGE || 'com.daigan.record')) throw new Error();
 res.setHeader('Cache-Control','no-store');
 return res.status(200).json({buildKey:data.buildKey,packageName:data.buildIdentifier,versionCode:Number(data.buildVersionNo),versionName:data.buildVersion,sizeBytes:Number(data.buildFileSize)});
 }catch{return res.status(502).json({error:'upstream unavailable'});}
}
