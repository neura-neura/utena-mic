using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;

namespace UtenaMic;

internal static class GitHubUpdate
{
    private const string Repository = "neura-neura/utena-mic";
    private const string Installer = "UtenaMic-VAC-Setup.exe";
    private const int MaxBytes = 256 * 1024 * 1024;
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromSeconds(20) };

    internal sealed record Release(string Tag, Uri Download, byte[] Digest, long Size);

    static GitHubUpdate()
    {
        Client.DefaultRequestHeaders.UserAgent.ParseAdd("UtenaMic/" + Assembly.GetExecutingAssembly().GetName().Version);
    }

    internal static async Task<Release?> Check(CancellationToken token)
    {
        using var response = await Client.GetAsync($"https://api.github.com/repos/{Repository}/releases/latest", token);
        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(token));
        var root = json.RootElement;
        string tag = root.GetProperty("tag_name").GetString() ?? "";
        if (!tag.StartsWith('v') || !Version.TryParse(tag[1..], out var version))
            throw new IOException("GitHub devolvió una versión desconocida.");
        var current = Assembly.GetExecutingAssembly().GetName().Version!;
        if (new Version(version.Major, version.Minor, Math.Max(0, version.Build)) <=
            new Version(current.Major, current.Minor, current.Build)) return null;

        string expectedUrl = $"https://github.com/{Repository}/releases/download/{tag}/{Installer}";
        foreach (var asset in root.GetProperty("assets").EnumerateArray())
        {
            if (asset.GetProperty("name").GetString() != Installer) continue;
            long size = asset.GetProperty("size").GetInt64();
            string? digest = asset.GetProperty("digest").GetString();
            if (asset.GetProperty("browser_download_url").GetString() != expectedUrl ||
                size is <= 0 or > MaxBytes || digest is not { Length: 71 } || !digest.StartsWith("sha256:"))
                break;
            return new Release(tag, new Uri(expectedUrl), Convert.FromHexString(digest[7..]), size);
        }
        throw new IOException("La versión de GitHub no incluye un instalador verificable.");
    }

    internal static async Task<string> Download(Release release, CancellationToken token)
    {
        string folder = Directory.CreateTempSubdirectory("UtenaMic-update-").FullName;
        string path = Path.Combine(folder, Installer);
        try
        {
            using var response = await Client.GetAsync(release.Download, HttpCompletionOption.ResponseHeadersRead, token);
            response.EnsureSuccessStatusCode();
            using var source = await response.Content.ReadAsStreamAsync(token);
            using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None, 65536, useAsync: true))
            {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = await source.ReadAsync(buffer, token)) > 0)
                {
                    if (file.Length + count > release.Size) throw new IOException("El instalador excede el tamaño publicado.");
                    await file.WriteAsync(buffer.AsMemory(0, count), token);
                }
                file.Position = 0;
                if (file.Length != release.Size ||
                    !CryptographicOperations.FixedTimeEquals(await SHA256.HashDataAsync(file, token), release.Digest))
                    throw new IOException("La descarga no coincide con el instalador publicado.");
            }
            return path;
        }
        catch
        {
            File.Delete(path);
            Directory.Delete(folder);
            throw;
        }
    }
}
