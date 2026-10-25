"""Run isolated PR56 fixtures with official Node and package uid; credentials on stdin."""
import io
import json
import pathlib
import shlex
import sys
import tarfile
import uuid
import hashlib
import paramiko

sys.stdout.reconfigure(encoding="utf-8")
config = json.load(sys.stdin)
repo = pathlib.Path(__file__).resolve().parent.parent
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode="w:gz") as archive:
    for pattern in ["fnos-app/package*.json", "fnos-app/src/*.mjs", "fnos-app/web/*", "fnos-app/test/**/*.mjs", "fnos-app/test/fixtures/**/*", "schedule-core/src/test/resources/**/*"]:
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
_, stdout, stderr = client.exec_command("tar -xzf " + remote + "/source.tar.gz -C " + remote)
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
stdin, stdout, stderr = client.exec_command("sudo -S -p '' chown -R dormpanel-schedule-manager:dormpanel-schedule-manager " + remote)
stdin.write(config["password"] + "\n")
stdin.flush()
stdin.channel.shutdown_write()
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
tests = config.get("tests", ["pr56-maintenance", "backup", "agent", "active-v3", "v3-migration", "history", "catalog", "write-api"])
assert all(name.replace("-", "").isalnum() for name in tests)
pattern = config.get("pattern")
assert pattern is None or isinstance(pattern, str) and len(pattern) <= 200
command = "cd " + remote + "/fnos-app && PATH=/var/apps/nodejs_v22/target/bin:$PATH npm ci --ignore-scripts --cache /tmp/pr56-npm-cache >/dev/null 2>&1 && cd .. && exec /var/apps/nodejs_v22/target/bin/node --max-old-space-size=192 --test " + ("--test-name-pattern=" + shlex.quote(pattern) + " " if pattern else "") + " ".join("fnos-app/test/" + name + ".test.mjs" for name in tests)
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
source = hashlib.sha256()
for path in sorted((repo / "fnos-app/src").glob("*.mjs")):
    source.update(path.name.encode())
    source.update(path.read_bytes())
print(json.dumps({"remote": remote, "exitCode": code, "sourceSha256": source.hexdigest()}))
client.close()
sys.exit(code)
