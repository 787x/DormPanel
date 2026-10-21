"""Real FN upload boundary capture. Secrets arrive on stdin, only private local logs contain endpoints."""
import subprocess,sys,json,uuid,time,pathlib,shlex,datetime
sys.stdin.reconfigure(encoding='utf-8-sig')
config=json.load(sys.stdin)
assert config['sender'] != config['peer']
assert config['parent'].startswith('https://')
out=pathlib.Path('artifacts/pr36/recovery')/('run-'+str(uuid.uuid4()));out.mkdir(parents=True,exist_ok=False)
print('Private evidence directory: '+str(out),flush=True)
runner='com.dormpanel.schedule.core.test/androidx.test.runner.AndroidJUnitRunner'
def adb(serial,*args):return subprocess.check_output(['adb','-s',serial,*args],stderr=subprocess.STDOUT,timeout=30).decode(errors='replace')
def command(serial,run,url,mode,phase):
 a=['am','instrument','-w','-e','class','com.dormpanel.app.schedule.RealUploadRecoveryTest']
 for k,v in dict(pr36Run=run,pr36Url=url,pr36User=config['user'],pr36Password=config['password'],pr36Mode=mode,pr36Phase=phase).items(): a+=['-e',k,v]
 return ['adb','-s',serial,'shell',' '.join(map(shlex.quote,a+[runner]))]
def phase(serial,run,url,mode,name):
 p=subprocess.run(command(serial,run,url,mode,name),capture_output=True,timeout=180);text=(p.stdout+p.stderr).decode(errors='replace');(out/f'{mode}-{name}.log').write_text(text,encoding='utf8')
 if p.returncode or 'OK (1 test)' not in text:raise RuntimeError(f'{mode}/{name} failed; inspect private log')
 print(f'{mode}/{name} PASS',flush=True)
records=[]
for mode in ['body','ack']:
 run=str(uuid.uuid4());url=config['parent'].rstrip('/')+'/pr36-recovery-'+run+'/'
 phase(config['sender'],run,url,mode,'prepare')
 f=(out/f'{mode}-interrupted.log').open('wb');p=subprocess.Popen(command(config['sender'],run,url,mode,'upload'),stdout=f,stderr=subprocess.STDOUT)
 marker='BODY_FLUSHED_EDIT_DURABLE' if mode=='body' else 'SERVER_ACK_'
 deadline=time.monotonic()+60;witness=None
 while time.monotonic()<deadline:
  logs=adb(config['sender'],'logcat','-d','-s','PR36Recovery:I','*:S')
  lines=[x for x in logs.splitlines() if run in x and marker in x]
  if lines:
   witness=lines[-1];break
  if p.poll() is not None:break
  time.sleep(.25)
 if not witness:
  adb(config['sender'],'shell','am','force-stop','com.dormpanel.schedule.core.test')
  p.wait(timeout=15);f.close()
  raise RuntimeError(f'{mode}: upload boundary NOT reached; no acceptance')
 pid=adb(config['sender'],'shell','pidof','com.dormpanel.schedule.core.test').strip()
 assert pid
 adb(config['sender'],'shell','am','force-stop','com.dormpanel.schedule.core.test')
 try:p.wait(timeout=15)
 except subprocess.TimeoutExpired:p.kill();p.wait()
 f.close()
 (out/f'{mode}-witness.txt').write_text(witness+'\nKilled sender PID '+pid+'\n'+datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),encoding='utf8')
 phase(config['sender'],run,url,mode,'recover')
 phase(config['peer'],run,url,mode,'peer')
 records.append(dict(mode=mode,run=run,url=url,witness=witness,pid=pid))
 (out/'results.private.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
print('REAL NETWORK PROCESS INTERRUPTION AND RECOVERY PASSED; isolated test package, not daily-data acceptance',flush=True)
