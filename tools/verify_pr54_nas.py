"""Isolated NAS verification; stdin JSON credentials, no installed files are modified."""
import io
import json
import pathlib
import sys
import tarfile
import uuid
import paramiko

config = json.load(sys.stdin)
repo = pathlib.Path(__file__).resolve().parent.parent
payload = io.BytesIO()
patterns = ["fnos-app/src/*.mjs", "fnos-app/test/v3*.mjs", "fnos-app/test/fixtures/v3*.mjs", "fnos-app/scripts/verify-v3-engine.mjs", "schedule-core/src/test/resources/sync-v3/*"]
workspace_regressions = ["writer", "store", "catalog", "security", "write-api", "history-observation", "migration", "backup"]
if config.get("mode") == "workspace":
    patterns += ["fnos-app/test/" + name + ".test.mjs" for name in workspace_regressions]
    patterns += ["fnos-app/test/fixtures/*", "fnos-app/test/fixtures/backup-0.4.0/*", "fnos-app/web/*", "schedule-core/src/test/resources/sync-v2/*", "schedule-core/src/test/resources/schedule/*"]
with tarfile.open(fileobj=payload, mode="w:gz") as archive:
    for pattern in patterns:
        for path in repo.glob(pattern):
            if path.is_file():
                archive.add(path, arcname=path.relative_to(repo).as_posix())
remote = "/tmp/dormpanel-pr54-" + str(uuid.uuid4())
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(config["host"], username=config["username"], password=config["password"], timeout=15)
sftp = client.open_sftp()
sftp.mkdir(remote, 0o755)
with sftp.open(remote + "/source.tar.gz", "wb") as out:
    out.write(payload.getvalue())
stdin, stdout, stderr = client.exec_command("tar -xzf " + remote + "/source.tar.gz -C " + remote + "; ln -s /var/apps/dormpanel-schedule-manager/target/server/node_modules " + remote + "/fnos-app/node_modules")
assert stdout.channel.recv_exit_status() == 0, stderr.read().decode()
# Reuse the package's OS flock + official Node runtime, under its non-root account.
mode = config.get("mode", "tests")
assert mode in ("tests", "interop", "targeted", "critical", "workspace")
entry = "--test fnos-app/test/v3.test.mjs fnos-app/test/v3-engine.test.mjs fnos-app/test/v3-metadata.test.mjs fnos-app/test/v3-recovery.test.mjs fnos-app/test/v3-index-boundaries.test.mjs"
if mode == "interop":
    entry = "fnos-app/scripts/verify-v3-engine.mjs"
elif mode == "targeted":
    entry = "--test --test-name-pattern='index:|publish:|journal:|merge:|candidates:|checkpoint:|offline:|receipt:|equivalent:|resume:|evidence:|pages:|atomic:|owner:|kill:|workspace:|index-fault:|reference:|decision:|fair:' fnos-app/test/v3-engine.test.mjs fnos-app/test/v3-metadata.test.mjs fnos-app/test/v3-recovery.test.mjs fnos-app/test/v3-index-boundaries.test.mjs"
elif mode == "critical":
    entry = "--test --test-name-pattern='threshold:|identity:' fnos-app/test/v3-engine.test.mjs"
elif mode == "workspace":
    entry = "--test fnos-app/test/v3-workspace-transaction.test.mjs fnos-app/test/v3-recovery.test.mjs " + " ".join("fnos-app/test/" + name + ".test.mjs" for name in workspace_regressions)
command = "cd " + remote + "; run=$(mktemp -d /tmp/dormpanel-pr54-writer-XXXXXXXX); exec 9>\"$run/.writer.lock\"; flock -n 9; id; /var/apps/nodejs_v22/target/bin/node --max-old-space-size=192 " + entry
# shlex keeps the inner test-name argument intact through sudo's shell.
import shlex
stdin, stdout, stderr = client.exec_command("sudo -S -p '' -u dormpanel-schedule-manager /bin/sh -c " + shlex.quote(command), timeout=600)
stdin.write(config["password"] + "\n")
if mode == "interop":
    probe = json.loads(pathlib.Path(config["probePath"]).read_text(encoding="utf-8"))
    stdin.write(json.dumps({"mode": "receive-publish", "config": probe["config"], "binding": probe["binding"], "origin": probe["origin"]}) + "\n")
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
