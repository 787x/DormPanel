"""Run isolated PR56 fixtures with official Node and package uid; credentials on stdin."""
import io
import json
import pathlib
import shlex
import sys
import tarfile
import uuid
import paramiko

sys.stdout.reconfigure(encoding="utf-8")
config = json.load(sys.stdin)
repo = pathlib.Path(__file__).resolve().parent.parent
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode="w:gz") as archive:
    for pattern in ["fnos-app/src/*.mjs", "fnos-app/web/*", "fnos-app/test/**/*.mjs", "fnos-app/test/fixtures/**/*", "schedule-core/src/test/resources/**/*"]:
        for path in repo.glob(pattern):
            if path.is_file():
                archive.add(path, arcname=path.relative_to(repo).as_posix())
remote = "/tmp/dormpanel-pr56-" + str(uuid.uuid4())
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(config["host"], username=config["username"], password=config["password"], timeout=15)
sftp = client.open_sftp()
sftp.mkdir(remote, 0o755)
with sftp.open(remote + "/source.tar.gz", "wb") as out:
    out.write(payload.getvalue())
_, stdout, stderr = client.exec_command("tar -xzf " + remote + "/source.tar.gz -C " + remote + " && ln -s /var/apps/dormpanel-schedule-manager/target/server/node_modules " + remote + "/fnos-app/node_modules")
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
tests = config.get("tests", ["pr56-maintenance", "backup", "agent", "active-v3", "v3-migration", "history", "catalog", "write-api"])
assert all(name.replace("-", "").isalnum() for name in tests)
command = "cd " + remote + " && exec /var/apps/nodejs_v22/target/bin/node --max-old-space-size=192 --test " + " ".join("fnos-app/test/" + name + ".test.mjs" for name in tests)
stdin, stdout, stderr = client.exec_command("sudo -S -p '' -u dormpanel-schedule-manager /bin/sh -c " + shlex.quote(command), timeout=600)
stdin.write(config["password"] + "\n")
stdin.flush()
stdin.channel.shutdown_write()
for line in stdout:
    print(line, end="", flush=True)
error = stderr.read().decode()
if error:
    print(error, file=sys.stderr)
code = stdout.channel.recv_exit_status()
print(json.dumps({"remote": remote, "exitCode": code}))
client.close()
sys.exit(code)
