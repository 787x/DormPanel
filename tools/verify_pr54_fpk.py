"""Build the final isolated FPK with the NAS official fnpack; never install it."""
import io
import json
import pathlib
import sys
import tarfile
import uuid
import paramiko

config = json.load(sys.stdin)
repo = pathlib.Path(__file__).resolve().parent.parent
project = repo / "fnos-app/build/dormpanel-schedule-manager"
assert (project / "manifest").is_file()
data = io.BytesIO()
with tarfile.open(fileobj=data, mode="w:gz") as archive:
    archive.add(project, arcname="project")
remote = "/tmp/dormpanel-pr54-fpk-" + str(uuid.uuid4())
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(config["host"], username=config["username"], password=config["password"], timeout=15)
sftp = client.open_sftp()
sftp.mkdir(remote, 0o700)
with sftp.open(remote + "/project.tar.gz", "wb") as f:
    f.write(data.getvalue())
command = ("cd " + remote + "; tar -xzf project.tar.gz; chmod 755 project/cmd/* project/app/bin/* project/app/server/*.sh; "
           "fnpack build --directory project")
stdin, stdout, stderr = client.exec_command(command, timeout=120)
print(stdout.read().decode("utf-8", errors="replace"))
error = stderr.read().decode("utf-8", errors="replace")
if error:
    print(error, file=sys.stderr)
assert stdout.channel.recv_exit_status() == 0, "official fnpack failed"
output = repo / "artifacts/pr54/final-fpk/dormpanel-schedule-manager.fpk"
output.parent.mkdir(parents=True, exist_ok=True)
sftp.get(remote + "/dormpanel-schedule-manager.fpk", str(output))
print(json.dumps({"remote": remote, "local": str(output), "installed": False}))
client.close()
