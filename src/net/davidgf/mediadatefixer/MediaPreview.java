package net.davidgf.mediadatefixer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.TextView;

import net.davidgf.mediadatefixer.core.MediaTypes;

import java.io.File;

/**
 * Tap-to-preview from the scan list: a downscaled image (or video thumbnail) decoded in-app, plus
 * "Open" to hand the file to the user's gallery / player through its MediaStore URI. Read-only:
 * nothing here touches the file.
 */
final class MediaPreview {
    private MediaPreview() {}

    static void show(Activity a, File f, CharSequence dates, CharSequence meta) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_media, null);
        ((TextView) v.findViewById(R.id.media_name)).setText(f.getName());
        ((TextView) v.findViewById(R.id.media_dates)).setText(dates);
        ((TextView) v.findViewById(R.id.media_meta)).setText(meta);
        ImageView image = v.findViewById(R.id.media_image);
        View loading = v.findViewById(R.id.media_loading);
        View none = v.findViewById(R.id.media_none);
        View play = v.findViewById(R.id.media_play);

        String ext = MediaTypes.ext(f.getName());
        boolean isImage = MediaTypes.IMAGE.contains(ext);
        boolean isVideo = MediaTypes.VIDEO.contains(ext);

        AlertDialog d = new AlertDialog.Builder(a)
                .setView(v)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.open, (di, w) -> open(a, f))
                .create();
        CancellationSignal cancel = new CancellationSignal();
        d.setOnDismissListener(di -> cancel.cancel());
        image.setOnClickListener(x -> open(a, f));
        play.setOnClickListener(x -> open(a, f));
        d.show();

        if (!isImage && !isVideo) {
            loading.setVisibility(View.GONE);
            none.setVisibility(View.VISIBLE);
            return;
        }
        int target = Math.min(a.getResources().getDisplayMetrics().widthPixels, 1600);
        new Thread(() -> {
            Drawable dr = null;
            try {
                if (isImage) {
                    dr = decodeImage(f, target);
                } else {
                    Bitmap b = ThumbnailUtils.createVideoThumbnail(f, new Size(target, target), cancel);
                    if (b != null) dr = new android.graphics.drawable.BitmapDrawable(a.getResources(), b);
                }
            } catch (Exception | OutOfMemoryError e) {
                // corrupt, unsupported or cancelled: show the placeholder
            }
            final Drawable result = dr;
            a.runOnUiThread(() -> {
                if (!d.isShowing()) return;
                loading.setVisibility(View.GONE);
                if (result == null) {
                    none.setVisibility(View.VISIBLE);
                    return;
                }
                image.setImageDrawable(result);
                image.setVisibility(View.VISIBLE);
                if (isVideo) play.setVisibility(View.VISIBLE);
                if (result instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) result).start();
            });
        }, "preview").start();
    }

    /** Decodes at roughly {@code target} px on the long side; EXIF rotation is applied by ImageDecoder. */
    private static Drawable decodeImage(File f, int target) throws java.io.IOException {
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(f), (dec, info, src) -> {
            int longSide = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
            int sample = 1;
            while (longSide / (sample * 2) >= target) sample *= 2;
            dec.setTargetSampleSize(sample);
        });
    }

    /** Opens the file in another app via its MediaStore URI (scanning it first if it is not indexed). */
    static void open(Activity a, File f) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MediaTypes.ext(f.getName()));
        new Thread(() -> {
            Uri uri = lookup(a, f, mime);
            if (uri != null) {
                a.runOnUiThread(() -> launch(a, uri, mime));
                return;
            }
            MediaScannerConnection.scanFile(a.getApplicationContext(), new String[] {f.getAbsolutePath()},
                    new String[] {mime}, (path, scanned) -> a.runOnUiThread(() -> launch(a, scanned, mime)));
        }, "open").start();
    }

    private static Uri lookup(Activity a, File f, String mime) {
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] cols = {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor c = a.getContentResolver().query(files, cols,
                MediaStore.Files.FileColumns.DATA + "=?", new String[] {f.getAbsolutePath()}, null)) {
            if (c == null || !c.moveToFirst()) return null;
            long id = c.getLong(0);
            Uri base;
            switch (c.getInt(1)) {
                case MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE: base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL); break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO: base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL); break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO: base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL); break;
                default: base = files;
            }
            return ContentUris.withAppendedId(base, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void launch(Activity a, Uri uri, String mime) {
        if (a.isDestroyed()) return;
        if (uri == null) {
            Ui.toast(a, a.getString(R.string.cant_open));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Ui.toast(a, a.getString(R.string.cant_open));
        }
    }
}
