"""真实设备 v1 迁移验收。UTF-8 stdin 提供 parent/user/password/devices；只操作 .testbed。"""
import base64,datetime,re,hashlib,json,pathlib,shlex,subprocess,sys,time,urllib.parse,urllib.request,uuid,xml.etree.ElementTree as ET
sys.stdin.reconfigure(encoding='utf-8-sig');config=json.load(sys.stdin)
assert config['parent'].startswith('https://')
out=pathlib.Path(config['cleanupDirectory']) if config.get('cleanupDirectory') else pathlib.Path('artifacts/pr36/migration')/('run-'+str(uuid.uuid4()))
assert pathlib.Path('artifacts/pr36/migration').resolve() in out.resolve().parents
if not config.get('cleanupDirectory'):out.mkdir(parents=True,exist_ok=False)
print('Private evidence: '+str(out),flush=True)
runner='com.dormpanel.timetable.testbed.test/androidx.test.runner.AndroidJUnitRunner'
auth='Basic '+base64.b64encode((config['user']+':'+config['password']).encode()).decode()
def adb(serial,*args):
 return subprocess.check_output(['adb','-s',serial,*args],stderr=subprocess.STDOUT,timeout=35).decode(errors='replace')
def native(serial,run,url,kind,phase,root_ui):
 args=['am','instrument','-w','-e','class','com.dormpanel.phone.PhoneRealV1MigrationTest']
 for k,v in dict(pr36MigrationRun=run,pr36MigrationUrl=url,pr36MigrationUser=config['user'],pr36MigrationPassword=config['password'],pr36MigrationCase=kind,pr36MigrationPhase=phase,pr35RootUi=str(root_ui).lower()).items():args+=['-e',k,v]
 return ['adb','-s',serial,'shell',' '.join(map(shlex.quote,args+[runner]))]
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect())
def http(url,method='GET'):
 r=urllib.request.Request(url,method=method,headers={'Authorization':auth,**({'Depth':'1'} if method=='PROPFIND' else {})})
 with opener.open(r,timeout=35) as response:return response.read()
def audit_remote(url,folder,label):
 result={}
 for node in ET.fromstring(http(url,'PROPFIND')).findall('{DAV:}response'):
  href=urllib.parse.urljoin(url,node.findtext('{DAV:}href'));name=urllib.parse.unquote(urllib.parse.urlsplit(href).path.rstrip('/').split('/')[-1])
  if name!='dormpanel-head-v1' and not name.endswith('.dps'):continue
  assert name=='dormpanel-head-v1' or re.fullmatch(r'snapshot-[0-9a-f-]{36}\.dps',name)
  assert href.startswith(url)
  b=http(href);result[name]=hashlib.sha256(b).hexdigest();(folder/(label+'-'+name)).write_bytes(b)
 (folder/(label+'-v1-hashes.json')).write_text(json.dumps(result,indent=2),encoding='utf8');return result
records=[]
if config.get('cleanupDirectory'):
 devices={d['role']:d['serial'] for d in config['devices']}
 entries=json.loads((out/'results.private.json').read_text(encoding='utf8'))
 for entry in entries:
  role=entry['device'];kind=entry['case'];run=entry['run'];url=entry['url']
  assert re.fullmatch('[a-z0-9_-]{1,40}',role) and role in devices
  assert kind in ['success','join','committed','uncommitted','missing','corrupt','dataset','unprovable','interrupted']
  assert url.startswith(config['parent'].rstrip('/')+'/pr36-migration-')
  assert str(uuid.UUID(run))==run and url.endswith('/pr36-migration-'+run+'-'+kind+'/')
  result=subprocess.run(native(devices[role],run,url,kind,'cleanup',False),capture_output=True,timeout=60)
  text=(result.stdout+result.stderr).decode(errors='replace');(out/(role+'-'+kind)/'cleanup.log').write_text(text,encoding='utf8')
  if result.returncode or 'OK (1 test)' not in text:raise RuntimeError('Isolated cleanup failed')
  print(role+'/'+kind+'/cleanup PASS',flush=True)
 sys.exit(0)
for device in config['devices']:
 assert re.fullmatch('[a-z0-9_-]{1,40}',device['role'])
 serial=device['serial'];root_ui=device.get('rootUi',False)
 for kind in device['cases']:
  assert kind in ['success','join','committed','uncommitted','missing','corrupt','dataset','unprovable','interrupted']
  run=str(uuid.uuid4());name='pr36-migration-'+run+'-'+kind;url=config['parent'].rstrip('/')+'/'+name+'/'
  folder=out/(device['role']+'-'+kind);folder.mkdir()
  def phase(p):
   try:result=subprocess.run(native(serial,run,url,kind,p,root_ui),capture_output=True,timeout=160)
   except subprocess.TimeoutExpired:
    adb(serial,'shell','am','force-stop','com.dormpanel.timetable.testbed')
    raise RuntimeError(device['role']+'/'+kind+'/'+p+' timed out; isolated process stopped') from None
   text=(result.stdout+result.stderr).decode(errors='replace');(folder/(p+'.log')).write_text(text,encoding='utf8')
   if result.returncode or 'OK (1 test)' not in text:
    adb(serial,'shell','am','force-stop','com.dormpanel.timetable.testbed')
    raise RuntimeError(device['role']+'/'+kind+'/'+p+' failed; inspect private log')
   print(device['role']+'/'+kind+'/'+p+' PASS',flush=True)
  phase('seed');before=audit_remote(url,folder,'before')
  if kind=='interrupted':
   with (folder/'interrupt.log').open('wb') as log:
    p=subprocess.Popen(native(serial,run,url,kind,'interrupt',root_ui),stdout=log,stderr=subprocess.STDOUT)
    try:
     deadline=time.monotonic()+100;witness=None
     while time.monotonic()<deadline:
      lines=adb(serial,'logcat','-d','-s','PR36Migration:I','*:S').splitlines()
      witness=next((line for line in lines if run in line and 'ARCHIVE_COMMITTED_TRANSACTION_OPEN' in line),None)
      if witness or p.poll() is not None:break
      time.sleep(.3)
     if not witness:raise RuntimeError('Migration transaction boundary NOT reached')
     pid=adb(serial,'shell','pidof','com.dormpanel.timetable.testbed').strip();assert pid
     (folder/'interruption-witness.txt').write_text(witness+'\nKilled isolated PID '+pid+'\n'+datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),encoding='utf8')
    finally:
     adb(serial,'shell','am','force-stop','com.dormpanel.timetable.testbed')
     try:p.wait(timeout=15)
     except subprocess.TimeoutExpired:p.kill();p.wait()
   phase('recover')
  else:
   phase('migrate')
   if kind in ['missing','corrupt','dataset','unprovable']:phase('verify-rejected')
  after=audit_remote(url,folder,'after');assert before==after,'v1 remote history changed'
  adb(serial,'pull','/sdcard/Android/data/com.dormpanel.timetable.testbed/files/'+name,str(folder/'device'))
  records.append(dict(device=device['role'],case=kind,run=run,url=url,v1Hashes=before,oldHistoryUnchanged=True))
  (out/'results.private.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
print('REAL ISOLATED V1 MANAGEMENT MIGRATION CHECKS PASSED; does not prove C01 daily v1 upgrade',flush=True)
