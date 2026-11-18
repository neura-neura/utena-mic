package local.utena.mic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;

final class AndroidUpdate {
    static final int INSTALL_PERMISSION = 31;
    private static final String REPOSITORY = "neura-neura/utena-mic";
    private static final String ASSET = "UtenaMic.apk";
    private static final int MAX_APK = 32 * 1024 * 1024;
    private final Activity activity;
    private final Button button;

    private static final class Release {
        final String tag, url, digest;
        final long size;

        Release(String tag, String url, String digest, long size) {
            this.tag = tag;
            this.url = url;
            this.digest = digest;
            this.size = size;
        }
    }

    AndroidUpdate(Activity activity, Button button) {
        this.activity = activity;
        this.button = button;
    }

    void check() {
        setBusy(true);
        new Thread(() -> {
            try {
                Release release = findRelease();
                deliver(() -> {
                    setBusy(false);
                    if (release == null) {
                        show("Ya tienes la última versión.");
                        return;
                    }
                    new AlertDialog.Builder(activity)
                        .setTitle("Utena Mic " + release.tag)
                        .setMessage("Descarga la actualización y confirma la instalación en Android.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Descargar e instalar", (dialog, which) -> download(release))
                        .show();
                });
            } catch (Exception error) {
                failed("No se pudo buscar la actualización", error);
            }
        }, "UtenaMic-update-check").start();
    }

    private Release findRelease() throws Exception {
        HttpsURLConnection connection = open(new URL("https://api.github.com/repos/" + REPOSITORY + "/releases/latest"));
        try {
            if (connection.getResponseCode() != 200) throw new IOException("GitHub respondió " + connection.getResponseCode() + ".");
            JSONObject result;
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (bytes.size() + read > 256 * 1024) throw new IOException("Respuesta de GitHub demasiado grande.");
                    bytes.write(buffer, 0, read);
                }
                result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            String tag = result.getString("tag_name");
            String current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"))
                throw new IOException("GitHub devolvió una versión desconocida.");
            if (compareVersions(tag.substring(1), current) <= 0) return null;

            String url = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + ASSET;
            JSONArray assets = result.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject item = assets.getJSONObject(i);
                if (!ASSET.equals(item.optString("name"))) continue;
                long size = item.optLong("size");
                String digest = item.optString("digest");
                if (size > 0 && size <= MAX_APK && digest.matches("sha256:[0-9a-f]{64}") &&
                        url.equals(item.optString("browser_download_url")))
                    return new Release(tag, url, digest.substring(7), size);
            }
            throw new IOException("La versión de GitHub no incluye un APK verificable.");
        } finally {
            connection.disconnect();
        }
    }

    private static int compareVersions(String first, String second) {
        String[] lhs = first.split("\\."), rhs = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int a = i < lhs.length ? Integer.parseInt(lhs[i]) : 0;
            int b = i < rhs.length ? Integer.parseInt(rhs[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static HttpsURLConnection open(URL url) throws IOException {
        HttpsURLConnection link = (HttpsURLConnection) url.openConnection();
        link.setConnectTimeout(10000);
        link.setReadTimeout(12000);
        link.setRequestProperty("User-Agent", "UtenaMic-Android");
        link.setInstanceFollowRedirects(false);
        return link;
    }

    private void download(Release release) {
        setBusy(true);
        new Thread(() -> {
            File partial = null;
            try {
                partial = File.createTempFile("update-", ".apk", activity.getCacheDir());
                fetchApk(release, partial);
                if (!validUpdate(partial)) throw new IOException("El APK no tiene la firma o versión esperada.");
                Files.move(partial.toPath(), UpdateApkProvider.file(activity).toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                deliver(() -> {
                    setBusy(false);
                    try {
                        if (!activity.getPackageManager().canRequestPackageInstalls()) {
                            new AlertDialog.Builder(activity)
                                .setMessage("Para instalar la actualización, permite que Utena Mic instale apps y vuelve aquí.")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Abrir ajustes", (dialog, which) -> requestInstallPermission())
                                .show();
                        } else launchInstaller();
                    } catch (RuntimeException error) {
                        show("No se pudo abrir el instalador de Android.");
                    }
                });
            } catch (Exception error) {
                failed("No se pudo descargar la actualización", error);
            } finally {
                if (partial != null) partial.delete();
            }
        }, "UtenaMic-update-download").start();
    }

    private static void fetchApk(Release release, File partial) throws Exception {
        URL url = new URL(release.url);
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpsURLConnection connection = open(url);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirección de GitHub incompleta.");
                    URL next = new URL(url, location);
                    String host = next.getHost();
                    if (!"https".equals(next.getProtocol()) || next.getUserInfo() != null ||
                        (next.getPort() != -1 && next.getPort() != 443) ||
                        (!"release-assets.githubusercontent.com".equals(host) && !"objects.githubusercontent.com".equals(host)))
                        throw new IOException("La descarga salió del servidor de GitHub.");
                    url = next;
                    continue;
                }
                if (status != 200) throw new IOException("GitHub respondió " + status + ".");
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long bytes = 0;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        bytes += read;
                        if (bytes > release.size) throw new IOException("El APK supera el tamaño publicado.");
                        sha.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                StringBuilder digest = new StringBuilder(64);
                for (byte part : sha.digest()) {
                    digest.append(Character.forDigit((part & 255) >> 4, 16));
                    digest.append(Character.forDigit(part & 15, 16));
                }
                if (bytes != release.size || !release.digest.contentEquals(digest))
                    throw new IOException("La descarga no coincide con el APK publicado.");
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Demasiadas redirecciones de GitHub.");
    }

    private boolean validUpdate(File file) throws PackageManager.NameNotFoundException {
        PackageManager manager = activity.getPackageManager();
        boolean modern = Build.VERSION.SDK_INT >= 28;
        int flags = modern ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(file.getPath(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) return false;
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), flags);
        long incoming = modern ? archive.getLongVersionCode() : archive.versionCode;
        long current = modern ? installed.getLongVersionCode() : installed.versionCode;
        Signature[] from = modern && archive.signingInfo != null ? archive.signingInfo.getApkContentsSigners() : archive.signatures;
        Signature[] to = modern && installed.signingInfo != null ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        return incoming > current && from != null && from.length > 0 && Arrays.equals(from, to);
    }

    private void requestInstallPermission() {
        try {
            activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())), INSTALL_PERMISSION);
        } catch (RuntimeException error) {
            show("No se pudieron abrir los ajustes de instalación.");
        }
    }

    void resumeInstall() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            show("Android sigue bloqueando la instalación desde Utena Mic.");
            return;
        }
        try {
            launchInstaller();
        } catch (RuntimeException error) {
            show("No se pudo abrir el instalador de Android.");
        }
    }

    private void launchInstaller() {
        if (!UpdateApkProvider.file(activity).isFile()) {
            show("La descarga ya no está disponible. Vuelve a buscar la actualización.");
            return;
        }
        Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/" + ASSET);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            activity.startActivity(intent.setAction(Intent.ACTION_VIEW));
        }
    }

    private void failed(String prefix, Exception error) {
        deliver(() -> {
            setBusy(false);
            show(prefix + ". " + (error.getMessage() == null ? "" : error.getMessage()));
        });
    }

    private void deliver(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
        });
    }

    private void setBusy(boolean busy) {
        button.setEnabled(!busy);
        button.setText(busy ? "Espere…" : "Actualizar app");
    }

    private void show(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
