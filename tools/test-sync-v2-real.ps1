param(
    [Parameter(Mandatory)][string]$DeviceA,
    [Parameter(Mandatory)][string]$DeviceB,
    [Parameter(Mandatory)][uri]$TestParent,
    [Parameter(Mandatory)][string]$CredentialFile,
    [string]$OutputDirectory = 'artifacts/pr35/real'
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$credential = Import-Clixml -LiteralPath $CredentialFile
$run = [guid]::NewGuid().ToString()
$base = $TestParent.AbsoluteUri.TrimEnd('/') + '/pr35-e2e-' + $run + '/'
$target = [IO.Path]::GetFullPath($OutputDirectory)
[void][IO.Directory]::CreateDirectory($target)
@{ Run=$run; Base=$base; DeviceA=$DeviceA; DeviceB=$DeviceB } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $target 'run.private.json') -Encoding utf8
function Begin-Test([string]$Serial,[string]$Phase,[string]$Role) {
    $info = [Diagnostics.ProcessStartInfo]::new('adb')
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $arguments = @('-s',$Serial,'shell','am','instrument','-w','-e','class','com.dormpanel.app.schedule.RealAppendOnlySyncTest',
        '-e','pr35RealRun',$run,'-e','pr35RealUrl',$base,'-e','pr35RealUser',$credential.UserName,
        '-e','pr35RealPassword',$credential.GetNetworkCredential().Password,'-e','pr35RealPhase',$Phase,'-e','pr35RealRole',$Role,
        'com.dormpanel.schedule.core.test/androidx.test.runner.AndroidJUnitRunner')
    # adb shell 会拼接参数；对 Android shell 逐项做单引号转义。
    $remoteArguments = $arguments[3..($arguments.Length - 1)] | ForEach-Object { "'" + $_.Replace("'", "'\''") + "'" }
    foreach ($arg in @('-s', $Serial, 'shell', ($remoteArguments -join ' '))) { $info.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($info)
    @{ Process=$process; Out=$process.StandardOutput.ReadToEndAsync(); Err=$process.StandardError.ReadToEndAsync(); Phase=$Phase; Role=$Role }
}
function Complete-Test($Task) {
    if (-not $Task.Process.WaitForExit(180000)) { $Task.Process.Kill(); throw '隔离真实测试超时；检查测试包状态' }
    $output = $Task.Out.GetAwaiter().GetResult() + $Task.Err.GetAwaiter().GetResult()
    [IO.File]::AppendAllText((Join-Path $target ($Task.Phase+'-'+$Task.Role+'.log')),$output,[Text.UTF8Encoding]::new($false))
    if ($Task.Process.ExitCode -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES|INSTRUMENTATION_FAILED') { throw ('真实隔离阶段失败：'+$Task.Phase+'/'+$Task.Role+'，详见本地日志') }
    $Task.Process.Dispose()
    Write-Host ($Task.Phase+'/'+$Task.Role+' PASSED')
}
Complete-Test (Begin-Test $DeviceA 'seed' 'a')
$a=Begin-Test $DeviceA 'prepare' 'a'; $b=Begin-Test $DeviceB 'prepare' 'b'
Complete-Test $a; Complete-Test $b
$a=Begin-Test $DeviceA 'upload' 'a'; $b=Begin-Test $DeviceB 'upload' 'b'
Complete-Test $a; Complete-Test $b
# 独立 Python 第三个 Writer，使用同一格式，基于原始根进行独立修改。
$handler=[Net.Http.HttpClientHandler]::new(); $handler.Credentials=$credential.GetNetworkCredential(); $handler.AllowAutoRedirect=$false
$client=[Net.Http.HttpClient]::new($handler); $client.Timeout=[TimeSpan]::FromSeconds(30)
try {
    $root=$client.GetByteArrayAsync($base+'commits/commit-'+$run+'.dpc').GetAwaiter().GetResult()
    $rootFile=Join-Path $target 'root.dpc'; $candidateFile=Join-Path $target 'python-writer.dpc'
    [IO.File]::WriteAllBytes($rootFile,$root)
    $python = @'
import importlib.util, pathlib, sys, uuid
spec=importlib.util.spec_from_file_location('syncv2',sys.argv[1]); ref=importlib.util.module_from_spec(spec); spec.loader.exec_module(ref)
c=ref.decode(pathlib.Path(sys.argv[2]).read_bytes())
c['parents']=[c['commitId']]; c['commitId']=str(uuid.uuid4()); c['writerId']=str(uuid.uuid4())
c['state']['dayAdjustments'][0]['label']='FPK future Python'
pathlib.Path(sys.argv[3]).write_bytes(ref.encode(c))
print(c['commitId'])
'@
    $identity = ($python | python - (Join-Path $repository 'tools/sync_v2_vectors.py') $rootFile $candidateFile).Trim()
    if ($LASTEXITCODE -ne 0 -or $identity -notmatch '^[0-9a-f-]{36}$') { throw '独立 Python 编码失败' }
    $bytes=[IO.File]::ReadAllBytes($candidateFile)
    $path=$base+'commits/commit-'+$identity+'.dpc'
    $before=$client.GetAsync($path).GetAwaiter().GetResult()
    try { if ([int]$before.StatusCode -ne 404) { throw '第三 Writer 随机路径已存在；停止写入' } } finally { $before.Dispose() }
    $res=$client.PutAsync($path,[Net.Http.ByteArrayContent]::new($bytes)).GetAwaiter().GetResult()
    try { if ([int]$res.StatusCode -notin @(200,201,204)) { throw '第三 Writer 普通 PUT 失败' } } finally { $res.Dispose() }
    $readback=$client.GetByteArrayAsync($path).GetAwaiter().GetResult()
    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($readback)) -ne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))) { throw '第三 Writer 回读校验失败' }
    Write-Host 'Independent Python writer PUT/GET/SHA256 PASSED'
} finally { $client.Dispose(); $handler.Dispose() }
$a=Begin-Test $DeviceA 'sync' 'a'; $b=Begin-Test $DeviceB 'sync' 'b'
Complete-Test $a; Complete-Test $b
Complete-Test (Begin-Test $DeviceA 'verify' 'a')
Complete-Test (Begin-Test $DeviceB 'verify' 'b')
$a=Begin-Test $DeviceA 'prepare-conflict' 'a'; $b=Begin-Test $DeviceB 'prepare-conflict' 'b'
Complete-Test $a; Complete-Test $b
$a=Begin-Test $DeviceA 'upload' 'a'; $b=Begin-Test $DeviceB 'upload' 'b'
Complete-Test $a; Complete-Test $b
$a=Begin-Test $DeviceA 'conflict' 'a'; $b=Begin-Test $DeviceB 'conflict' 'b'
Complete-Test $a; Complete-Test $b
Complete-Test (Begin-Test $DeviceB 'resolve' 'b')
Complete-Test (Begin-Test $DeviceA 'verify-resolution' 'a')
Complete-Test (Begin-Test $DeviceB 'verify-resolution' 'b')
Write-Host 'REAL ISOLATED THREE-WRITER AND CONFLICT SYNC PASSED (not full RC acceptance)'
