param([Parameter(Mandatory)][string]$UnsignedApk,[Parameter(Mandatory)][string]$Apk,[Parameter(Mandatory)][string]$KeyFile)
$ErrorActionPreference='Stop'
function Join-Bytes {
    param([byte[][]]$Parts)
    $stream=[IO.MemoryStream]::new()
    try { foreach($part in $Parts) { $stream.Write($part,0,$part.Length) }; return ,$stream.ToArray() }
    finally { $stream.Dispose() }
}
function Prefix([byte[]]$Data) {
    return ,(Join-Bytes @([BitConverter]::GetBytes([uint32]$Data.Length),$Data))
}
function Segment([byte[]]$Data,[int]$Start,[int]$Count) {
    $copy=[byte[]]::new($Count)
    [Array]::Copy($Data,$Start,$copy,0,$Count)
    return ,$copy
}
$apkData=[IO.File]::ReadAllBytes($UnsignedApk)
$eocdOffset=$apkData.Length-22
while($eocdOffset -ge [Math]::Max(0,$apkData.Length-65557)) {
    if([BitConverter]::ToUInt32($apkData,$eocdOffset) -eq 0x06054b50 -and
        [BitConverter]::ToUInt16($apkData,$eocdOffset+20) -eq $apkData.Length-$eocdOffset-22){break}
    $eocdOffset--
}
if($eocdOffset -lt [Math]::Max(0,$apkData.Length-65557)){throw 'Invalid ZIP central directory'}
$directoryOffset=[int][BitConverter]::ToUInt32($apkData,$eocdOffset+16)
$blocks=@((Segment $apkData 0 $directoryOffset),(Segment $apkData $directoryOffset ($eocdOffset-$directoryOffset)),(Segment $apkData $eocdOffset ($apkData.Length-$eocdOffset)))
$digests=[Collections.Generic.List[byte[]]]::new()
foreach($block in $blocks){
    for($offset=0;$offset -lt $block.Length;$offset+=1048576){
        $chunk=Segment $block $offset ([Math]::Min(1048576,$block.Length-$offset))
        $digests.Add([Security.Cryptography.SHA256]::HashData((Join-Bytes @([byte[]]@(0xa5),(Prefix $chunk)))))
    }
}
$digest=[Security.Cryptography.SHA256]::HashData((Join-Bytes (@([byte[]]@(0x5a),[BitConverter]::GetBytes([uint32]$digests.Count)) + $digests.ToArray())))
if(Test-Path -LiteralPath $KeyFile){
    $cert=[Security.Cryptography.X509Certificates.X509CertificateLoader]::LoadPkcs12FromFile($KeyFile,'',[Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    $rsa=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($cert)
}else{
    $rsa=[Security.Cryptography.RSA]::Create(2048)
    $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=Utena Mic local build',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $cert=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1),[DateTimeOffset]::UtcNow.AddYears(12))
    [IO.File]::WriteAllBytes($KeyFile,$cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12,''))
}
$algorithm=[BitConverter]::GetBytes([uint32]0x0103)
$signedData=Join-Bytes @((Prefix (Prefix (Join-Bytes @($algorithm,(Prefix $digest))))),(Prefix (Prefix $cert.RawData)),(Prefix ([byte[]]::new(0))))
$signature=$rsa.SignData($signedData,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
$signer=Join-Bytes @((Prefix $signedData),(Prefix (Prefix (Join-Bytes @($algorithm,(Prefix $signature))))),(Prefix $rsa.ExportSubjectPublicKeyInfo()))
$value=Prefix (Prefix $signer)
$idValue=Join-Bytes @([BitConverter]::GetBytes([uint32]0x7109871a),$value)
$entry=Join-Bytes @([BitConverter]::GetBytes([uint64]$idValue.Length),$idValue)
$size=[BitConverter]::GetBytes([uint64]($entry.Length+24))
$signingBlock=Join-Bytes @($size,$entry,$size,[Text.Encoding]::ASCII.GetBytes('APK Sig Block 42'))
[Array]::Copy([BitConverter]::GetBytes([uint32]($directoryOffset+$signingBlock.Length)),0,$blocks[2],16,4)
[IO.File]::WriteAllBytes($Apk,(Join-Bytes @($blocks[0],$signingBlock,$blocks[1],$blocks[2])))
$cert.Dispose()
$rsa.Dispose()
