import subprocess,json,sys,uuid,pathlib,requests,paramiko,tarfile,io,time,shlex,socketserver,socket,select,threading,atexit
sys.stdout.reconfigure(encoding='utf8')
args=json.load(sys.stdin);assert all(p in ['v2','v3'] for p in args.get('protocols',['v2','v3'])); root=pathlib.Path(__file__).resolve().parent.parent
logdir=root/'artifacts/pr65/network';logdir.mkdir(parents=True,exist_ok=True)
ssh=paramiko.SSHClient();ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy());ssh.connect(args['host'],username=args['username'],password=args['password'],timeout=15)
atexit.register(ssh.close)
remote='/tmp/dormpanel-pr65-network-'+str(uuid.uuid4());sftp=ssh.open_sftp();sftp.mkdir(remote,0o700)
payload=io.BytesIO()
with tarfile.open(fileobj=payload,mode='w:gz') as tar:
 for p in (root/'fnos-app/src').glob('*.mjs'):tar.add(p,arcname='src/'+p.name)
with sftp.file(remote+'/source.tgz','wb') as f:f.write(payload.getvalue())
sftp.mkdir(remote+'/scripts')
sftp.put(str(root/'fnos-app/scripts/verify-course-batch.mjs'),remote+'/scripts/verify-course-batch.mjs')
_,out,err=ssh.exec_command('tar -xzf '+remote+'/source.tgz -C '+remote+' && ln -s /var/apps/dormpanel-pr56/target/server/node_modules '+remote+'/node_modules')
assert out.channel.recv_exit_status()==0,err.read().decode()
def adb(serial,*argv):
 r=subprocess.run(['adb','-s',serial,*argv],capture_output=True,text=True,encoding='utf8',errors='replace');assert r.returncode==0,r.stdout+r.stderr;return r.stdout
class Relay(socketserver.BaseRequestHandler):
 def handle(self):
  with socket.create_connection((args['host'],5005),timeout=30) as upstream:
   peers=[self.request,upstream]
   while True:
    ready,_,_=select.select(peers,[],[],60)
    if not ready:return
    for source in ready:
     data=source.recv(65536)
     if not data:return
     (upstream if source is self.request else self.request).sendall(data)
relay=socketserver.ThreadingTCPServer(('127.0.0.1',0),Relay);relay.daemon_threads=True
threading.Thread(target=relay.serve_forever,daemon=True).start();port=relay.server_address[1]
atexit.register(relay.shutdown)
atexit.register(lambda: (root/'build/pr65-network.private.json').unlink(missing_ok=True))
roles={'phone':('210b601e','com.dormpanel.timetable.testbed','phone-app'),'x08e':('28605/A0UH93770','com.dormpanel.app.testbed','app')}
for serial,pkg,module in roles.values():
 adb(serial,'reverse',f'tcp:{port}',f'tcp:{port}')
 atexit.register(lambda serial=serial,pkg=pkg: subprocess.run(['adb','-s',serial,'shell','run-as',pkg,'rm','-f','files/pr65-network.json'],capture_output=True))
 atexit.register(lambda serial=serial: subprocess.run(['adb','-s',serial,'reverse','--remove',f'tcp:{port}'],capture_output=True))
 for apk in [root/module/'build/outputs/apk/x08eTest'/f'{module}-x08eTest.apk',root/module/'build/outputs/apk/androidTest/x08eTest'/f'{module}-x08eTest-androidTest.apk']:
  print('Install',serial,apk.name,flush=True);assert 'Success' in adb(serial,'install','-r',str(apk))
def android(role,conf,phase):
 serial,pkg,_=roles[role];conf={**conf,'phase':phase};path=root/'build/pr65-network.private.json';path.write_text(json.dumps(conf),encoding='utf8')
 adb(serial,'push',str(path),'/data/local/tmp/pr65-network.json')
 adb(serial,'shell','run-as',pkg,'mkdir','-p','files')
 adb(serial,'shell','run-as',pkg,'cp','/data/local/tmp/pr65-network.json','files/pr65-network.json')
 adb(serial,'shell','rm','/data/local/tmp/pr65-network.json')
 text=adb(serial,'shell','am','instrument','-w','-r','-e','class','com.dormpanel.app.schedule.testing.CourseBatchNetworkTest',pkg+'.test/androidx.test.runner.AndroidJUnitRunner')
 (logdir/f'{conf["protocol"]}-{role}-{phase}.log').write_text(text,encoding='utf8');assert 'OK (1 test)' in text,text[-3500:]
 target=logdir/f'{conf["protocol"]}-{role}-{phase}.json';adb(serial,'pull',f'/sdcard/Android/data/{pkg}/files/pr65-network-{conf["protocol"]}.json',str(target))
 report=json.loads(target.read_text(encoding='utf8'));print(role,conf['protocol'],phase,'PASS',flush=True);return report
def node(proto,mode,config=None):
 command='PR65_ISOLATED_ROOT='+remote+' /var/apps/nodejs_v22/target/bin/node '+remote+'/scripts/verify-course-batch.mjs'
 si,so,se=ssh.exec_command(command,timeout=180);si.write(json.dumps({'protocol':proto,'mode':mode,'config':config}));si.channel.shutdown_write();out=so.read().decode();error=se.read().decode();assert so.channel.recv_exit_status()==0,error
 report=json.loads(out.strip().splitlines()[-1]);(logdir/f'{proto}-fnos-{mode}.json').write_text(json.dumps(report,ensure_ascii=False),encoding='utf8');print('fnOS',proto,mode,report['result'],flush=True);return report
for proto in args.get('protocols',['v2','v3']):
 run=str(uuid.uuid4());url=f'http://{args["host"]}:5005/dormpanel/pr65-{proto}-{run}/'
 response=requests.request('MKCOL',url,auth=(args['username'],args['password']),timeout=15);assert response.status_code==201,response.status_code
 conf={'protocol':proto,'run':run,'url':url.replace(f'{args["host"]}:5005',f'127.0.0.1:{port}'),'username':args['username'],'password':args['password']}
 seed=android('phone',conf,'seed')
 if proto=='v3':conf['binding']=seed['binding']
 android('phone',conf,'phone-edit')
 node(proto,'join',{'url':url,'username':args['username'],'password':args['password'],'allowInsecureHttp':True})
 android('x08e',conf,'x08e-edit');node(proto,'edit')
 phone=android('phone',conf,'final');x08e=android('x08e',conf,'final');fnos=node(proto,'final')
 assert phone['rawHash']==x08e['rawHash']==fnos['rawHash'],(phone,x08e,fnos)
 print(proto,'THREE-ENDPOINT HASH MATCH',phone['rawHash'],flush=True)
 print('isolated URL:',url,flush=True)
for serial,pkg,module in roles.values():
 adb(serial,'reverse','--remove',f'tcp:{port}')
 adb(serial,'shell','run-as',pkg,'rm','files/pr65-network.json')
relay.shutdown();ssh.close()
