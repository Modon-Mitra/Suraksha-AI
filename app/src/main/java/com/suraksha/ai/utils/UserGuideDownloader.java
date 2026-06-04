package com.suraksha.ai.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * UserGuideDownloader — copies the bundled PDF user guide
 * from assets/ to the public Downloads folder and opens it.
 *
 * Usage (from SettingsActivity):
 *   UserGuideDownloader.download(this);
 */
public class UserGuideDownloader {

    private static final String ASSET_NAME  = "suraksha_user_guide.pdf";
    private static final String OUTPUT_NAME = "Suraksha_AI_User_Guide.pdf";

    public static void download(Context context) {
        new Thread(() -> {
            try {
                // Copy from assets → Downloads folder
                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();

                File outFile = new File(downloadsDir, OUTPUT_NAME);

                InputStream  in  = context.getAssets().open(ASSET_NAME);
                OutputStream out = new FileOutputStream(outFile);

                byte[] buf = new byte[8192];
                int    n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                in.close();
                out.close();

                // Show success toast and open the PDF on main thread
                android.os.Handler handler =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> {
                    Toast.makeText(context,
                            "User guide saved to Downloads!", Toast.LENGTH_SHORT).show();
                    openPdf(context, outFile);
                });

            } catch (Exception e) {
                android.os.Handler handler =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() ->
                        Toast.makeText(context,
                                "Download failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }, "GuideDownloadThread").start();
    }

    private static void openPdf(Context context, File file) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".provider",
                    file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // No PDF viewer installed — guide is still in Downloads
            Toast.makeText(context,
                    "Saved to Downloads folder. Open with any PDF viewer.",
                    Toast.LENGTH_LONG).show();
        }
    }
}