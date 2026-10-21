"""独立核对真实迁移验收导出的 v1/v2、Room checkpoint 及历史字节；不访问网络或凭据。"""
import base64,hashlib,importlib.util,json,pathlib,sys,urllib.error,urllib.parse,urllib.request,xml.etree.ElementTree as ET
spec=importlib.util.spec_from_file_location('vectors',pathlib.Path(__file__).with_name('sync_v2_vectors.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
def sha(b):return hashlib.sha256(b).hexdigest()
def snapshot(b):
 r=v.Reader(b);assert r.take(4)==b'DPSS' and r.num()==1
 n=r.num();payload=r.take(n);assert r.take(32)==hashlib.sha256(payload).digest() and r.pos==len(b)
 q=v.Reader(payload);metadata=q.row('dataset:T revision:T parent:N createdAt:L generation:L');state={}
 for (key,schema),limit in zip(v.SCHEMAS,[10000,10000,16,50000,1000,5000]):state[key]=q.rows(lambda:q.row(schema),limit)
 def profile():
  p=q.row('termKey:T timezone:T week1Monday:T')
  def phase():return dict(effectiveFrom=q.text(),periods=q.rows(lambda:q.row('periodNumber:I start:T end:T'),30))
  p['phases']=q.rows(phase,128);return p
 state['profiles']=q.rows(profile,128);state['customProfileKeys']=q.rows(q.text,128)
 assert q.pos==len(payload)
 return metadata,canonical(state)
def canonical(state):
 result={}
 for key,rows in state.items():
  identity={'dayAdjustments':'date','classOverrides':'key','profiles':'termKey'}.get(key,'id')
  result[key]=sorted(rows) if key=='customProfileKeys' else sorted(rows,key=lambda r:r[identity])
 return result
def statehash(state):return sha(json.dumps(state,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode())
def prove_v1(folder, device_folder):
 baseline,_=snapshot((device_folder/'before-checkpoint-1.bin').read_bytes())
 head=(folder/'before-dormpanel-head-v1').read_bytes().decode('ascii').split('\n')
 assert len(head)==5 and head[0]=='DormPanelSchedule/1' and head[4]==''
 if head[1]!=baseline['dataset']:return 'DATASET_MISMATCH'
 revision=head[2];generation=None
 for step in range(256):
  path=folder/('before-snapshot-'+revision+'.dps')
  if not path.exists():return 'MISSING_ANCESTOR'
  data=path.read_bytes()
  try:meta,_=snapshot(data)
  except (AssertionError,IndexError,UnicodeError):return 'CORRUPT_ANCESTOR'
  assert meta['revision']==revision and meta['dataset']==baseline['dataset']
  if step==0:assert sha(data)==head[3]
  if generation is not None:assert meta['generation']+1==generation
  if revision==baseline['revision']:
   assert data==(device_folder/'before-checkpoint-1.bin').read_bytes()
   return 'PROVEN'
  if meta['parent'] is None:return 'UNPROVABLE'
  revision=meta['parent'];generation=meta['generation']
 raise AssertionError('History bound exceeded')
def audit(root):
 records=[]
 for entry in json.loads((root/'results.private.json').read_text(encoding='utf8')):
  case=entry['case'];folder=root/(entry['device']+'-'+case);d=folder/'device'
  before=json.loads((d/'before.json').read_text());after=json.loads((d/'after.json').read_text())
  _,old=snapshot((d/'before.dps').read_bytes());_,new=snapshot((d/'after.dps').read_bytes());_,expected=snapshot((d/'expected.dps').read_bytes())
  rejected=case in ['missing','corrupt','dataset','unprovable']
  proof=prove_v1(folder,d)
  assert proof=={'missing':'MISSING_ANCESTOR','corrupt':'CORRUPT_ANCESTOR','dataset':'DATASET_MISMATCH','unprovable':'UNPROVABLE'}.get(case,'PROVEN')
  assert new==(old if rejected else expected),(entry['device'],case,'state mismatch')
  assert before['credentialsDecryptUnchanged'] and after['credentialsDecryptUnchanged']
  for label,report in [('before',before),('after',after)]:
   for key,value in report['checkpoint'].items():assert sha((d/f'{label}-checkpoint-{key}.bin').read_bytes())==value
   remote=json.loads((folder/(label+'-v1-hashes.json')).read_text())
   assert remote==report['v1Remote']
   for name,value in remote.items():assert sha((folder/(label+'-'+name)).read_bytes())==value
  assert before['v1Remote']==after['v1Remote']
  old_check={k:val for k,val in before['checkpoint'].items() if 1<=int(k)<=7}
  if rejected:
   assert all(after['checkpoint'].get(k)==val for k,val in old_check.items())
   assert '21' not in after['checkpoint'] and '23' not in after['checkpoint']
   _,reopened=snapshot((d/'reopened.dps').read_bytes());assert reopened==old
  else:
   assert all(after['checkpoint'][str(110+int(k))]==val for k,val in old_check.items())
   assert (d/'after-checkpoint-100.bin').read_bytes()==(d/'before.dps').read_bytes()
   assert '4' not in after['checkpoint'] and '23' not in after['checkpoint']
   commit=v.decode((d/'after-checkpoint-21.bin').read_bytes());assert canonical(commit['state'])==new and commit['datasetId']==entry['run']
  if case=='interrupted':
   _,reopened=snapshot((d/'reopened.dps').read_bytes());assert reopened==old
   reopened_report=json.loads((d/'reopened.json').read_text());assert all(reopened_report['checkpoint'].get(k)==val for k,val in old_check.items())
   assert 'ARCHIVE_COMMITTED_TRANSACTION_OPEN' in (folder/'interruption-witness.txt').read_text()
  records.append(dict(device=entry['device'],case=case,result='PASS',independentHistoryProof=proof,beforeStateSha256=statehash(old),afterStateSha256=statehash(new),expectedStateSha256=statehash(old if rejected else expected),counts=after['counts'],oldCheckpoint=old_check,checkpointAfter=after['checkpoint'],v1RemoteSha256=before['v1Remote'],v1HistoryUnchanged=True,credentialsDecryptUnchanged=True,status=(d/'status.txt').read_text(encoding='utf8')))
 return records
def audit_online(root,records,account):
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):return None
 opener=urllib.request.build_opener(NoRedirect())
 auth='Basic '+base64.b64encode((account['user']+':'+account['password']).encode()).decode()
 def get(url,method='GET'):
  assert url.startswith(account['parent'].rstrip('/')+'/pr36-migration-') and url.startswith('https://')
  request=urllib.request.Request(url,method=method,headers={'Authorization':auth,**({'Depth':'1'} if method=='PROPFIND' else {})})
  return opener.open(request,timeout=35).read()
 entries={(e['device'],e['case']):e for e in json.loads((root/'results.private.json').read_text(encoding='utf8'))}
 for record in records:
  entry=entries[(record['device'],record['case'])];base=entry['url'];folder=root/(record['device']+'-'+record['case'])
  # Verify that the entire observed old file set, including orphan/invalid fixtures, remains unchanged.
  old={}
  for node in ET.fromstring(get(base,'PROPFIND')).findall('{DAV:}response'):
   url=urllib.parse.urljoin(base,node.findtext('{DAV:}href'));name=urllib.parse.unquote(urllib.parse.urlsplit(url).path.rstrip('/').split('/')[-1])
   if name=='dormpanel-head-v1' or name.endswith('.dps'):old[name]=sha(get(url))
  assert old==record['v1RemoteSha256']
  directory=base+'dormpanel-sync-v2/'
  if record['case'] in ['missing','corrupt','dataset','unprovable']:
   try:get(directory,'PROPFIND')
   except urllib.error.HTTPError as error:assert error.code==404
   else:raise AssertionError('Rejected migration created a v2 directory')
   record['remoteV2Absent']=True;continue
  commits={};hashes={}
  for node in ET.fromstring(get(directory,'PROPFIND')).findall('{DAV:}response'):
   url=urllib.parse.urljoin(directory,node.findtext('{DAV:}href'))
   if url.rstrip('/')==directory.rstrip('/'):continue
   assert url.startswith(directory) and url.endswith('.dpc')
   data=get(url);commit=v.decode(data);identity=commit['commitId']
   assert commit['datasetId']==entry['run'] and identity not in commits
   commits[identity]=commit;hashes[identity]=sha(data)
   (folder/('online-commit-'+identity+'.dpc')).write_bytes(data)
  parents={parent for c in commits.values() for parent in c['parents']};assert parents<=commits.keys()
  tips=set(commits)-parents;assert len(tips)==1
  _,expected=snapshot((folder/'device/after.dps').read_bytes())
  actual=canonical(commits[tips.pop()]['state']);assert actual==expected
  record['remoteV2CommitHashes']=hashes;record['remoteV2CanonicalStateSha256']=statehash(actual)

if __name__=='__main__':
 root=pathlib.Path(sys.argv[1]);records=audit(root)
 if '--online' in sys.argv:
  sys.stdin.reconfigure(encoding='utf-8-sig');audit_online(root,records,json.load(sys.stdin))
 output=pathlib.Path(sys.argv[2]) if len(sys.argv)>2 and sys.argv[2]!='--online' else root/'independent-audit.json'
 output.write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
 print(f'{len(records)} independent migration audits PASS; {output}')
