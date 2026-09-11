# 可访问网站配置文件协议与 Windows 生成器

本文档对应 Lightning Browser 的 `.lbconfig` 导入功能。APP 已配置解密密钥和验签公钥；签名私钥必须只保存在受控的 Windows 电脑上。

## 目录约定

```text
AllowlistGenerator/
  AllowlistGenerator.csproj
  Program.cs
  keys/
    signing-private.pem
  input/
    sites.txt
  output/
    sites.lbconfig
```

`input/sites.txt` 使用 UTF-8，一行一个域名。空行和以 `#` 开头的行会被忽略：

```text
# 该项也允许 news.example.com 等子域名
example.com
学校.example.cn
```

只填写域名，不要填写协议、端口、路径或通配符。生成器会转换中文域名、转为小写、移除末尾的点和开头的 `www.`，然后去重。

## 可直接使用的 Windows 生成器

安装 .NET 8 SDK 后，在目录中创建以下两个文件。

`AllowlistGenerator.csproj`：

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
  </PropertyGroup>
</Project>
```

`Program.cs`：

```csharp
using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

const string inputPath = "input/sites.txt";
const string outputPath = "output/sites.lbconfig";
const byte keyId = 1;
byte[] magic = Encoding.ASCII.GetBytes("LBCFG001");
byte[] aesKey = Convert.FromHexString(
    "51e358691384305b847970148cf77ab1123bccc0e3a41b81620ff8fe691fb812");
string privateKeyPem = File.ReadAllText("keys/signing-private.pem", Encoding.ASCII);

var idn = new IdnMapping { UseStd3AsciiRules = true };
string NormalizeDomain(string source)
{
    if (source != source.Trim() || source.Any(c => c is '/' or ':' || char.IsWhiteSpace(c)))
        throw new InvalidDataException($"非法域名：{source}");

    string value = source.TrimEnd('.');
    string ascii = idn.GetAscii(value).ToLowerInvariant();
    if (ascii.StartsWith("www.")) ascii = ascii[4..];
    string[] labels = ascii.Split('.');
    if (ascii.Length is < 1 or > 253 || labels.Length < 2 ||
        labels.Any(label => label.Length is < 1 or > 63 ||
            label[0] == '-' || label[^1] == '-'))
        throw new InvalidDataException($"非法域名：{source}");
    return ascii;
}

var domains = File.ReadLines(inputPath, Encoding.UTF8)
    .Where(line => !string.IsNullOrWhiteSpace(line) && !line.TrimStart().StartsWith('#'))
    .Select(NormalizeDomain)
    .Distinct(StringComparer.Ordinal)
    .Order(StringComparer.Ordinal)
    .ToArray();
if (domains.Length is < 1 or > 2000)
    throw new InvalidDataException("名单必须包含 1 到 2000 个域名");

byte[] plaintext = Encoding.UTF8.GetBytes(string.Join('\n', domains));
byte[] nonce = RandomNumberGenerator.GetBytes(12);
byte[] ciphertext = new byte[plaintext.Length];
byte[] tag = new byte[16];
byte[] aad = [.. magic, keyId];
using (var aes = new AesGcm(aesKey, tag.Length))
    aes.Encrypt(nonce, plaintext, ciphertext, tag, aad);
byte[] encrypted = [.. ciphertext, .. tag];

using var signedStream = new MemoryStream();
signedStream.Write(magic);
signedStream.WriteByte(keyId);
signedStream.Write(nonce);
Span<byte> intBuffer = stackalloc byte[4];
BinaryPrimitives.WriteInt32BigEndian(intBuffer, encrypted.Length);
signedStream.Write(intBuffer);
signedStream.Write(encrypted);
byte[] signedBytes = signedStream.ToArray();

using var signer = ECDsa.Create();
signer.ImportFromPem(privateKeyPem);
byte[] signature = signer.SignData(
    signedBytes,
    HashAlgorithmName.SHA256,
    DSASignatureFormat.Rfc3279DerSequence);
if (signature.Length > ushort.MaxValue) throw new CryptographicException("签名过长");

Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
using var output = File.Create(outputPath);
output.Write(signedBytes);
Span<byte> shortBuffer = stackalloc byte[2];
BinaryPrimitives.WriteUInt16BigEndian(shortBuffer, (ushort)signature.Length);
output.Write(shortBuffer);
output.Write(signature);
Console.WriteLine($"已生成 {outputPath}，包含 {domains.Length} 个域名");
```

运行：

```powershell
dotnet run
```

每次生成都会使用新的随机 nonce，因此相同名单产生不同的文件，这是正常现象。签名私钥位于本机被 Git 忽略的 `docs/allowlist-signing-private-key.local.md`；把其中 PEM 内容保存为 `keys/signing-private.pem`。签名私钥和 AES 密钥必须保持不变，否则当前 APP 无法导入。

## 二进制格式

所有整数均为无符号或有符号的网络字节序（大端），文件上限为 1 MiB。

| 顺序 | 长度 | 内容 |
|---|---:|---|
| 1 | 8 | ASCII `LBCFG001` |
| 2 | 1 | 密钥编号，当前为 `1` |
| 3 | 12 | AES-GCM 随机 nonce |
| 4 | 4 | 密文长度，包含末尾 16 字节 GCM tag |
| 5 | 可变 | AES-256-GCM 密文和 tag |
| 6 | 2 | DER 签名长度 |
| 7 | 可变 | ECDSA P-256/SHA-256 DER 签名 |

AES-GCM 的附加认证数据为 `magic + keyId`。ECDSA 签名覆盖从文件开头到密文/tag 末尾的全部字节，不包含签名长度和签名本身。解密后的内容是 UTF-8 文本，一行一个已经规范化的 ASCII/Punycode 域名。

APP 内置的 X.509 SubjectPublicKeyInfo 公钥为：

```text
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXtbsRto7NbZzPZsKttuUUjSXS9JCbxGJnJ/tC0p+vgHkxycYHXuzDKPqfp5o2V/7Bbcs6V5uXTA1kbf2h+Uw2w==
```

APP 会先验签，再解密并验证全部域名，最后以一次同步写入合并到现有名单。配置文件可以随时导入；原来的手动添加仍只在北京时间 22:00–23:00 开放。重复域名不会产生重复项，任一条目非法则整份文件拒绝导入。
