"""在 fnOS 应用 UID 的独立 /tmp 目录运行 PR69 Linux 检查；连接配置仅由 stdin 输入。
不部署 FPK、不修改日用工作区。复用既有隔离应用的依赖与 Node 22。
"""
import pathlib,io,tarfile,paramiko,shlex,uuid,json,sys
config=json.load(sys.stdin)
root=pathlib.Path(__file__).resolve().parent.parent;remote='/tmp/dormpanel-pr69-'+str(uuid.uuid4());client=paramiko.SSHClient();client.set_missing_host_key_policy(paramiko.AutoAddPolicy());client.connect(config['host'],username=config['username'],password=config['password'],timeout=15)
payload=io.BytesIO()
with tarfile.open(fileobj=payload,mode='w:gz') as t:
 for pattern in ['fnos-app/src/*.mjs','fnos-app/test/**/*.mjs','fnos-app/test/fixtures/**/*','fnos-app/scripts/*.mjs','fnos-app/web/**/*','fnos-app/package*.json','schedule-core/src/test/resources/**/*','test-vectors/**/*']:
  for p in root.glob(pattern):
   if p.is_file():t.add(p,arcname=p.relative_to(root).as_posix())
sftp=client.open_sftp();sftp.mkdir(remote,0o755)
with sftp.file(remote+'/source.tgz','wb') as f:f.write(payload.getvalue())
# Reuse the installed app's dependencies, without touching its code or data.
setup='tar -xzf '+remote+'/source.tgz -C '+remote+' && ln -s /var/apps/dormpanel-pr56/target/server/node_modules '+remote+'/fnos-app/node_modules && chown -R dormpanel-schedule-manager:dormpanel-schedule-manager '+remote
si,so,se=client.exec_command("sudo -S -p '' /bin/sh -c "+shlex.quote(setup));si.write(config['password']+'\n');si.flush();si.channel.shutdown_write();assert so.channel.recv_exit_status()==0,se.read().decode()
pathlib.Path('build/pr69-nas-root.txt').write_text(remote)
# WSL 可复用测试机已安装的 Node 22 ELF；不安装或升级服务器运行时。
sftp.get('/var/apps/nodejs_v22/target/bin/node',str(root/'build/pr69-linux-node'))
tests=['shared-records','shared-state','v3','v3-workspace-transaction','v3-recovery','v3-migration','v3-initialization-recovery','active-v3','backup','pr56-maintenance']
command='cd '+remote+' && /var/apps/nodejs_v22/target/bin/node --version && id && /var/apps/nodejs_v22/target/bin/node --test '+' '.join('fnos-app/test/'+n+'.test.mjs' for n in tests)
si,so,se=client.exec_command("sudo -S -p '' -u dormpanel-schedule-manager /bin/sh -c "+shlex.quote(command),timeout=600);si.write(config['password']+'\n');si.flush();si.channel.shutdown_write()
with open('build/pr69-nas-linux.log','w',encoding='utf8') as f:
 for line in so:f.write(line);f.flush()
 errors=se.read().decode();f.write(errors)
code=so.channel.recv_exit_status();print('NAS tests exit',code,'isolated root',remote);print(pathlib.Path('build/pr69-nas-linux.log').read_text(encoding='utf8')[-6500:]);client.close()

sys.exit(code)
