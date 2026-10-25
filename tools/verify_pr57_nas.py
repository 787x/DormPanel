"""Run isolated PR57 fixtures with official Node and package uid; credentials on stdin."""
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
    for pattern in ["fnos-app/package*.json", "fnos-app/src/*.mjs", "fnos-app/scripts/*.mjs", "fnos-app/web/*", "fnos-app/test/**/*.mjs", "fnos-app/test/fixtures/**/*", "schedule-core/src/test/resources/**/*"]:
        for path in repo.glob(pattern):
            if path.is_file():
                archive.add(path, arcname=path.relative_to(repo).as_posix())
remote = "/tmp/dormpanel-pr57-" + str(uuid.uuid4())
source = hashlib.sha256()
with tarfile.open(fileobj=io.BytesIO(payload.getvalue()), mode="r:gz") as archived:
    for member in sorted((m for m in archived.getmembers() if m.name.startswith("fnos-app/src/") and m.name.endswith(".mjs")), key=lambda m: m.name):
        source.update(pathlib.PurePosixPath(member.name).name.encode())
        source.update(archived.extractfile(member).read())
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(config["host"], username=config["username"], password=config["password"], timeout=15)
sftp = client.open_sftp()
sftp.mkdir(remote, 0o755)
if config.get("webdavProbe"):
    with sftp.open(remote + "/probe.json", "w") as out:
        out.write(json.dumps(config["webdavProbe"]))
    sftp.chmod(remote + "/probe.json", 0o600)
with sftp.open(remote + "/source.tar.gz", "wb") as out:
    out.write(payload.getvalue())
_, stdout, stderr = client.exec_command("tar -xzf " + remote + "/source.tar.gz -C " + remote)
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
stdin, stdout, stderr = client.exec_command("sudo -S -p '' chown -R dormpanel-schedule-manager:dormpanel-schedule-manager " + remote)
stdin.write(config["password"] + "\n")
stdin.flush()
stdin.channel.shutdown_write()
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
tests = config.get("tests", ["pr57-endpoint", "agent", "catalog", "pr56-maintenance"])
assert all(name.replace("-", "").isalnum() for name in tests)
pattern = config.get("pattern")
assert pattern is None or isinstance(pattern, str) and len(pattern) <= 200
command = "cd " + shlex.quote(remote) + "/fnos-app && PATH=/var/apps/nodejs_v22/target/bin:$PATH npm ci --ignore-scripts --cache /tmp/pr57-npm-cache >/dev/null 2>&1 && cd .. && PR57_TLS_HOST=" + shlex.quote(config["host"]) + " /var/apps/nodejs_v22/target/bin/node --max-old-space-size=192 --test " + ("--test-name-pattern=" + shlex.quote(pattern) + " " if pattern else "") + " ".join("fnos-app/test/" + name + ".test.mjs" for name in tests)
if config.get("webdavProbe"):
    command += " && /var/apps/nodejs_v22/target/bin/node fnos-app/scripts/verify-v3-endpoint.mjs < probe.json"
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
if code == 0 and config.get("privateProbeOutput"):
    # Local private test input only; never print credentials or add it to evidence.
    target = pathlib.Path(config["privateProbeOutput"]).resolve()
    assert target.name == ".pr57-probe.json" and target.parent == repo
    secret_in, secret_out, secret_error = client.exec_command("sudo -S -p '' cat " + shlex.quote(remote + "/android-probe.json"))
    secret_in.write(config["password"] + "\n"); secret_in.flush(); secret_in.channel.shutdown_write()
    private_bytes = secret_out.read()
    assert secret_out.channel.recv_exit_status() == 0, "private probe retrieval failed"
    target.write_bytes(private_bytes)
print(json.dumps({"remote": remote, "exitCode": code, "sourceSha256": source.hexdigest()}))
client.close()
sys.exit(code)
