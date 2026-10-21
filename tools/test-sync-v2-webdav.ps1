param(
    [Parameter(Mandatory)][uri]$TestParent,
    [Parameter(Mandatory)][string]$CredentialFile,
    [string]$OutputFile
)
# 仅在显式指定目录下创建随机隔离 collection；不读写课表/head，不删除远端文件。
$ErrorActionPreference = 'Stop'
if ($TestParent.Scheme -notin @('http','https') -or $TestParent.UserInfo -or $TestParent.Query -or $TestParent.Fragment) { throw '需要无 userinfo/query/fragment 的 HTTP(S) 独立测试目录。' }
$credential = Import-Clixml -LiteralPath $CredentialFile
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.Credentials = $credential.GetNetworkCredential()
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
$directory = $TestParent.AbsoluteUri.TrimEnd('/') + '/pr35-v2-' + [guid]::NewGuid().ToString() + '/'
function Hash([byte[]]$Bytes) { [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant() }
function Request([string]$Method,[string]$Url,[byte[]]$Bytes,[bool]$Depth = $false) {
    $req = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Url)
    if ($Depth) { $req.Headers.Add('Depth','1') }
    if ($null -ne $Bytes) { $req.Content = [Net.Http.ByteArrayContent]::new($Bytes) }
    try {
        $response = $client.SendAsync($req).GetAwaiter().GetResult()
        try {
            $data = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            if ($data.Length -gt 524288) { throw '测试响应超限' }
            @{ Status = [int]$response.StatusCode; Bytes = $data }
        } finally { $response.Dispose() }
    } finally { $req.Dispose() }
}
try {
    $created = Request 'MKCOL' $directory $null
    if ($created.Status -ne 201) { throw '无法创建全新隔离目录；停止写入' }
    $objects = 1..2 | ForEach-Object { @{ Name = ('writer-' + [guid]::NewGuid().ToString() + '.txt'); Bytes = [Text.Encoding]::UTF8.GetBytes('DormPanel PR35 synthetic ' + [guid]::NewGuid()) } }
    $requests = @(); $tasks = @()
    foreach ($obj in $objects) {
        $req = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Put, $directory + $obj.Name)
        $req.Content = [Net.Http.ByteArrayContent]::new($obj.Bytes)
        $requests += $req; $tasks += $client.SendAsync($req)
    }
    [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks)
    for ($i=0; $i -lt 2; $i++) {
        $res = $tasks[$i].GetAwaiter().GetResult()
        try { if ([int]$res.StatusCode -notin @(200,201,204)) { throw '并发普通 PUT 失败' } }
        finally { $res.Dispose(); $requests[$i].Dispose() }
        $get = Request 'GET' ($directory + $objects[$i].Name) $null
        if ($get.Status -ne 200 -or (Hash $get.Bytes) -ne (Hash $objects[$i].Bytes)) { throw 'GET 回读内容/SHA-256 错误' }
    }
    $observations = @(); $found = $false
    for ($attempt=1; $attempt -le 3; $attempt++) {
        $listing = Request 'PROPFIND' $directory $null $true
        if ($listing.Status -ne 207) { throw 'Depth:1 列举失败' }
        $settings = [Xml.XmlReaderSettings]::new(); $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit; $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create([IO.MemoryStream]::new($listing.Bytes), $settings)
        $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null
        try { $xml.Load($reader) } finally { $reader.Dispose() }
        $ns = [Xml.XmlNamespaceManager]::new($xml.NameTable); $ns.AddNamespace('d','DAV:')
        $hrefs = @($xml.SelectNodes('/d:multistatus/d:response/d:href',$ns) | ForEach-Object InnerText)
        if ($hrefs.Count -ne @($hrefs | Select-Object -Unique).Count) { throw '重复列举结果' }
        $count = @($objects | Where-Object { $name=$_.Name; @($hrefs | Where-Object { ([uri]::new([uri]$directory,$_)).AbsolutePath.EndsWith('/'+$name) }).Count -eq 1 }).Count
        $observations += @{ Attempt=$attempt; Discovered=$count }
        if ($count -eq 2) { $found = $true; break }
    }
    if (-not $found) { throw '列举延迟/缺失：三次观察后未发现全部文件' }
    $report = @{ ShanghaiTime=[DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).ToString('o'); Result='BASIC PREFLIGHT PASSED'; Observations=$observations; Objects=@($objects | ForEach-Object { @{Name=$_.Name; SHA256=(Hash $_.Bytes)} }); Scope='合成文件；不代表完整 Sync v2/RC 验收'; DirectoryName=([uri]$directory).Segments[-1] } | ConvertTo-Json -Depth 5
    $report
    if ($OutputFile) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile),$report,[Text.UTF8Encoding]::new($false)) }
} finally { $client.Dispose(); $handler.Dispose() }
